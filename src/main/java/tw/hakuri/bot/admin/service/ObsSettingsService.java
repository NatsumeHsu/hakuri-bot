package tw.hakuri.bot.admin.service;
import java.util.*;
import org.springframework.stereotype.Service;
import tw.hakuri.bot.settings.service.SettingsStore;
@Service
public class ObsSettingsService {
 public static final Map<String,String> DEFAULTS = Map.of("OBS_FONT_FAMILY","Microsoft JhengHei","OBS_FONT_SIZE","42","OBS_FONT_COLOR","#FFFFFF");
 private final SettingsStore store;
 public ObsSettingsService(SettingsStore store) { this.store=store; }
 public Map<String,String> style() {
  var result=new TreeMap<>(DEFAULTS); var saved=store.read();
  DEFAULTS.keySet().forEach(k -> { if(saved.containsKey(k)) result.put(k,saved.get(k)); }); return result;
 }
 public static void validate(Map<String,String> values) {
  String font=values.getOrDefault("OBS_FONT_FAMILY", "");
  if (!font.matches("[\\p{L}\\p{N} _-]{1,100}")) throw new IllegalArgumentException("請填入 1–100 字的字型名稱");
  try { int size=Integer.parseInt(values.get("OBS_FONT_SIZE")); if(size<16 || size>160) throw new NumberFormatException(); }
  catch(NumberFormatException ex) { throw new IllegalArgumentException("字幕大小需為 16–160 的整數"); }
  if(!values.getOrDefault("OBS_FONT_COLOR", "").matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("字幕顏色需為 #RRGGBB 格式");
 }
}
