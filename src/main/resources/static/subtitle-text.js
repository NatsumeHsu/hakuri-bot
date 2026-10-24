'use strict';
// Display-only filtering; the reply API and model actions keep their original text.
window.subtitleText = text => String(text || '')
  .replace(/\[\s*(?:neutral|happy|happiness|joy|excited|sad|sadness|angry|anger|shy|surprised|surprise|fear|disgust|confused|smile|laugh|cry|pout|middle_finger|controller|tablet|tablet_tap|tongue|red_eyes)\s*\]/gi, '')
  .trim();
