// Combray: whether a tap landed on a media element that has no data, which in this engine means
// one whose load was cancelled (native/src/media.rs), or on controls a page laid over it. The
// engine fills in the tap's point in CSS pixels of the viewport and, on true, has the
// application offer the page's media again: a player that shows its own error, or its own play
// button, then leads back to Play.
//
// The point is matched against each media element's box, not against the hit stack: custom
// players lay their controls over the video and often take it out of hit testing
// (pointer-events: none). The hit stack serves two things. It reaches the open shadow roots under
// the point, where such players keep their <video>. And it tells the player from something drawn
// over it: the topmost element at the point must be the media element, contain it, or lie within
// the media element's parent, so a tap on a banner over the video does not count. Closed shadow
// roots and frames are not searched.
//
// The page's realm runs this, so a page can make it answer either way; a yes only offers again
// what the page itself loaded.
(function (x, y) {
  "use strict";

  var MAX_ROOTS = 16;
  var MAX_HITS = 64;
  var MAX_MEDIA = 64;
  var HAVE_NOTHING = 0;

  var roots = [document];
  for (var r = 0; r < roots.length; r++) {
    var hits = roots[r].elementsFromPoint(x, y);
    for (var i = 0; i < hits.length && i < MAX_HITS; i++) {
      var shadow = hits[i].shadowRoot;
      if (shadow && roots.length < MAX_ROOTS && roots.indexOf(shadow) < 0) roots.push(shadow);
    }
  }

  for (var s = 0; s < roots.length; s++) {
    var media = roots[s].querySelectorAll("video, audio");
    for (var m = 0; m < media.length && m < MAX_MEDIA; m++) {
      if (tapped(roots[s], media[m])) return true;
    }
  }
  return false;

  function tapped(root, element) {
    if (element.readyState !== HAVE_NOTHING) return false;
    var box = element.getBoundingClientRect();
    if (!(box.width > 0 && box.height > 0)) return false;
    if (!(x >= box.left && x <= box.right && y >= box.top && y <= box.bottom)) return false;
    var top = root.elementFromPoint(x, y);
    if (!top) return false;
    var parent = element.parentNode;
    return top === element || top.contains(element) || (!!parent && parent.contains(top));
  }
})(__X__, __Y__)
