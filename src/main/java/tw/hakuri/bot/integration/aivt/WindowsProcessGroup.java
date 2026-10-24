package tw.hakuri.bot.integration.aivt;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.BaseTSD.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** A non-inheritable Job handle is owned by this JVM, including during forced JVM termination. */
final class WindowsProcessGroup implements AiProcessGroup {
    public interface Jobs extends StdCallLibrary {
        Jobs INSTANCE = Native.load("kernel32", Jobs.class, W32APIOptions.UNICODE_OPTIONS);
        HANDLE CreateJobObject(WinBase.SECURITY_ATTRIBUTES attributes, String name);
        boolean SetInformationJobObject(HANDLE job, int infoClass, Pointer info, int size);
        boolean AssignProcessToJobObject(HANDLE job, HANDLE process);
        boolean TerminateJobObject(HANDLE job, int code);
        int ResumeThread(HANDLE thread);
    }
    @Structure.FieldOrder({"processTime", "jobTime", "flags", "minimumWorkingSet", "maximumWorkingSet", "activeProcesses", "affinity", "priority", "scheduling"})
    public static class BasicLimits extends Structure {
        public long processTime, jobTime;
        public int flags;
        public SIZE_T minimumWorkingSet = new SIZE_T(), maximumWorkingSet = new SIZE_T();
        public int activeProcesses;
        public ULONG_PTR affinity = new ULONG_PTR();
        public int priority, scheduling;
    }
    @Structure.FieldOrder({"basic", "io", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory"})
    public static class ExtendedLimits extends Structure {
        public BasicLimits basic = new BasicLimits();
        public WinNT.IO_COUNTERS io = new WinNT.IO_COUNTERS();
        public SIZE_T processMemory = new SIZE_T(), jobMemory = new SIZE_T(), peakProcessMemory = new SIZE_T(), peakJobMemory = new SIZE_T();
    }
    private final Kernel32 kernel = Kernel32.INSTANCE;
    private final Jobs jobs = Jobs.INSTANCE;
    private final List<NativeChild> children = new ArrayList<>();
    private HANDLE job;

    WindowsProcessGroup() throws IOException {
        job = jobs.CreateJobObject(null, null);
        if (job == null) throw failure("CreateJobObject");
        var limits = new ExtendedLimits();
        limits.basic.flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        limits.write();
        if (!jobs.SetInformationJobObject(job, 9, limits.getPointer(), limits.size())) {
            IOException error = failure("SetInformationJobObject"); kernel.CloseHandle(job); job = null; throw error;
        }
    }
    @Override public synchronized Child start(AiServiceCommand command, Path log) throws IOException {
        if (job == null) throw new IOException("AI process group is closed");
        var security = new WinBase.SECURITY_ATTRIBUTES(); security.bInheritHandle = true;
        HANDLE output = kernel.CreateFile(log.toAbsolutePath().toString(), 0x4, 0x3, security, 4, 0x80, null); // append, OPEN_ALWAYS
        if (WinBase.INVALID_HANDLE_VALUE.equals(output)) throw failure("CreateFile log");
        HANDLE input = kernel.CreateFile("NUL", WinNT.GENERIC_READ, 0x3, security, 3, 0, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(input)) { IOException error = failure("CreateFile NUL"); kernel.CloseHandle(output); throw error; }
        var startup = new WinBase.STARTUPINFO();
        startup.cb = new WinDef.DWORD(startup.size()); startup.dwFlags = 0x100; // STARTF_USESTDHANDLES
        startup.hStdInput = input; startup.hStdOutput = output; startup.hStdError = output;
        var info = new WinBase.PROCESS_INFORMATION();
        boolean created = false, assigned = false;
        try {
            // Suspend before assigning the job: no child can escape between CreateProcess and Assign.
            String line = command.command().stream().map(WindowsProcessGroup::quote).collect(Collectors.joining(" "));
            var environment = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            environment.putAll(System.getenv());
            environment.put("PYTHONUNBUFFERED", "1"); environment.put("PYTHONIOENCODING", "utf-8"); environment.put("PYTHONUTF8", "1");
            String block = environment.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("\0")) + "\0\0";
            byte[] encoded = block.getBytes(StandardCharsets.UTF_16LE);
            try (var nativeEnvironment = new Memory(encoded.length)) {
                nativeEnvironment.write(0, encoded, 0, encoded.length);
                if (!kernel.CreateProcessW(command.command().getFirst(), Native.toCharArray(line), null, null, true,
                        new WinDef.DWORD(0x08000000 | 0x4 | 0x400), nativeEnvironment, command.directory().toAbsolutePath().toString(), startup, info))
                    throw failure("CreateProcess " + command.name());
            }
            created = true;
            if (!jobs.AssignProcessToJobObject(job, info.hProcess)) throw failure("AssignProcessToJobObject");
            assigned = true;
            if (jobs.ResumeThread(info.hThread) == -1) throw failure("ResumeThread");
            var child = new NativeChild(info.hProcess, info.dwProcessId.longValue());
            children.add(child); return child;
        } catch (IOException error) {
            if (created) { kernel.TerminateProcess(info.hProcess, 1); kernel.CloseHandle(info.hProcess); }
            if (assigned) close();
            throw error;
        } finally {
            if (created) kernel.CloseHandle(info.hThread);
            kernel.CloseHandle(input); kernel.CloseHandle(output);
        }
    }
    static String quote(String value) {
        var result = new StringBuilder("\""); int slashes = 0;
        for (char ch : value.toCharArray()) {
            if (ch == '\\') { slashes++; continue; }
            result.append("\\".repeat(ch == '"' ? slashes * 2 + 1 : slashes)).append(ch); slashes = 0;
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString();
    }
    private IOException failure(String operation) { return new IOException(operation + " failed, Windows error=" + kernel.GetLastError()); }
    private final class NativeChild implements Child {
        private final HANDLE handle;
        private final long pid;
        private Integer stoppedExit;
        NativeChild(HANDLE handle, long pid) { this.handle = handle; this.pid = pid; }
        @Override public long pid() { return pid; }
        @Override public boolean alive() { synchronized (WindowsProcessGroup.this) {
            return stoppedExit == null && kernel.WaitForSingleObject(handle, 0) == 258; // WAIT_TIMEOUT
        } }
        @Override public int exitCode() { synchronized (WindowsProcessGroup.this) {
            if (stoppedExit != null) return stoppedExit;
            var code = new IntByReference(); kernel.GetExitCodeProcess(handle, code); return code.getValue();
        } }
    }
    @Override public synchronized void close() {
        if (job == null) return;
        jobs.TerminateJobObject(job, 1);
        kernel.CloseHandle(job); job = null; // kills all descendants, including ones whose parents already exited
        for (NativeChild child : children) {
            kernel.WaitForSingleObject(child.handle, 2000);
            child.stoppedExit = child.exitCode(); kernel.CloseHandle(child.handle);
        }
        children.clear();
    }
}
