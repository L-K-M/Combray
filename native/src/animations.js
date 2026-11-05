// Combray: Element.animate() for pages, until Servo implements Web Animations.
//
// Servo 0.6.0 has no Element.animate(): its Web Animations interfaces are incomplete stubs behind
// a pref. Pages that call it unconditionally break; docs.mistral.ai shows "Application error"
// when its marquee starts. This script runs in every document before the page's own scripts and
// adds stubs for the three members pages probe first: animate(), getAnimations() and the
// document's getAnimations(). The first use of any of them installs the web-animations-js polyfill
// and hands over to it. The constructors and document.timeline still appear only then.
// The polyfill wraps requestAnimationFrame, getComputedStyle and the style of animated elements,
// so pages that never animate keep the engine's own behaviour.
//
//   el.animate(...) ──▶ stub: removes itself, runs the polyfill ──▶ the polyfill's animate()
//
// The stub steps aside when the engine has animate() at all, and the polyfill when the engine's
// is complete. BrowsingTest#pagesCanAnimateElements fails if a future Servo's is not.
(function (installPolyfill) {
  "use strict";

  if (typeof Element === "undefined" || "animate" in Element.prototype) {
    return;
  }

  var installed = false;

  // A page rarely probes animate() alone: `"animate" in Element.prototype` is usually followed by
  // `el.getAnimations()` or `document.getAnimations()`. Each of the three steps aside on first use,
  // so any of them brings the whole surface into existence instead of leaving the others missing.
  Object.defineProperty(Element.prototype, "animate", {
    configurable: true,
    writable: true,
    value: function animate(keyframes, options) {
      ensureInstalled();
      return this.animate(keyframes, options);
    }
  });

  Object.defineProperty(Element.prototype, "getAnimations", {
    configurable: true,
    writable: true,
    value: function getAnimations() {
      ensureInstalled();
      return this.getAnimations();
    }
  });

  Object.defineProperty(Document.prototype, "getAnimations", {
    configurable: true,
    writable: true,
    value: function getAnimations() {
      ensureInstalled();
      return this.getAnimations();
    }
  });

  // The polyfill wraps whatever animate() it finds, so the stubs have to be gone before it runs:
  // left in place it would wrap this one and call it again for ever. Nothing after that point
  // restores them, so a failed install must not leave a stub that delegates into thin air.
  function ensureInstalled() {
    if (installed) {
      return;
    }
    installed = true;

    delete Element.prototype.animate;
    delete Element.prototype.getAnimations;
    delete Document.prototype.getAnimations;
    try {
      installPolyfill();
      addComputedTiming();
      addDocumentAnimations();
      // Both are what the stubs above promised; a polyfill that provides one and not the other
      // would leave the second as a TypeError with nothing to say why.
      ["animate", "getAnimations"].forEach(function (name) {
        if (typeof Element.prototype[name] !== "function") {
          throw new Error("Combray: web-animations-js did not define Element.prototype." + name);
        }
      });
      acceptPropertyIndexedKeyframes();
    } catch (error) {
      // Without this, every later call says "this.animate is not a function" and blames the page.
      failWith(error);
      throw error;
    }
  }

  /** Keeps the real cause reachable: animations are unavailable, and the page is told why. */
  function failWith(error) {
    ["animate", "getAnimations"].forEach(function (name) {
      Object.defineProperty(Element.prototype, name, {
        configurable: true,
        writable: true,
        value: function () { throw error; }
      });
    });
    Object.defineProperty(Document.prototype, "getAnimations", {
      configurable: true,
      writable: true,
      value: function () { throw error; }
    });
  }

  // 2.3.2 converts a property-indexed keyframe object by giving every keyframe the whole
  // `offset`, `easing` and `composite` member: a list there throws "Keyframe offsets must be
  // numbers.", and a single offset lands on every keyframe instead of the first. Both entry points
  // get the keyframe sequence the spec derives instead; a sequence passes through untouched.
  //   {opacity: [0, 0.5, 1], offset: [0, 0.2]} → [{opacity: 0, offset: 0},
  //                                               {opacity: 0.5, offset: 0.2}, {opacity: 1}]
  function acceptPropertyIndexedKeyframes() {
    var polyfillAnimate = Element.prototype.animate;
    Object.defineProperty(Element.prototype, "animate", {
      configurable: true,
      writable: true,
      value: function animate(keyframes, options) {
        return polyfillAnimate.call(this, keyframeSequence(keyframes), options);
      }
    });

    var PolyfillEffect = window.KeyframeEffect;
    if (typeof PolyfillEffect !== "function") {
      return;
    }
    // The polyfill constructs effects through this global too (clone(), groups), passing its
    // internal id as a fourth argument.
    var KeyframeEffect = function KeyframeEffect(target, keyframes, options, id) {
      return new PolyfillEffect(target, keyframeSequence(keyframes), options, id);
    };
    KeyframeEffect.prototype = PolyfillEffect.prototype;
    window.KeyframeEffect = KeyframeEffect;
  }

  // https://drafts.csswg.org/web-animations-1/#process-a-keyframes-argument, property-indexed
  // case: each property's values get evenly spaced offsets, keyframes at the same offset merge,
  // and the offset, easing and composite lists then go to the keyframes in order (easing and
  // composite repeat when shorter). The polyfill validates the result as it does any sequence.
  function keyframeSequence(keyframes) {
    if (keyframes === null || typeof keyframes !== "object" || Array.isArray(keyframes)) {
      return keyframes;
    }
    // A keyframe sequence given as another iterable (a Set, a generator): the polyfill looks for
    // `Array.prototype.from`, which does not exist, and would read it as property-indexed.
    if (typeof Symbol === "function" && typeof keyframes[Symbol.iterator] === "function") {
      return Array.from(keyframes);
    }

    // Only animatable properties make keyframes; any other member would add a frame that takes
    // one of the offsets. A CSS property is a string member of an element's style (`cssFloat`,
    // not `float`), which leaves out `length` and `item`; `cssText` is the one other string.
    var style = document.createElement("div").style;
    var frames = [];
    Object.keys(keyframes).forEach(function (property) {
      if (property === "offset" || property === "easing" || property === "composite") {
        return;
      }
      var cssProperty = typeof style[property] === "string" && property !== "cssText";
      if (property.indexOf("--") !== 0 && !cssProperty) {
        return;
      }
      var values = asList(keyframes[property]);
      values.forEach(function (value, index) {
        var offset = values.length === 1 ? 1 : index / (values.length - 1);
        var frame = frames.filter(function (entry) { return entry.offset === offset; })[0];
        if (!frame) {
          frame = { offset: offset, keyframe: {} };
          frames.push(frame);
        }
        frame.keyframe[property] = value;
      });
    });
    frames.sort(function (a, b) { return a.offset - b.offset; });
    var sequence = frames.map(function (frame) { return frame.keyframe; });

    asList(keyframes.offset).forEach(function (offset, index) {
      if (index < sequence.length) {
        sequence[index].offset = offset;
      }
    });
    ["easing", "composite"].forEach(function (member) {
      var values = asList(keyframes[member]);
      if (values.length === 0) {
        return;
      }
      sequence.forEach(function (keyframe, index) {
        var value = values[index % values.length];
        // "auto" defers to the effect's own mode; the polyfill knows only "replace" and rejects it.
        if (member === "composite" && value === "auto") {
          return;
        }
        keyframe[member] = value;
      });
    });
    return sequence;
  }

  /**
   * A keyframe member as the list the spec reads: absent is empty, an array or other iterable
   * object is a sequence, and anything else (a string included) is one item.
   */
  function asList(value) {
    if (value === undefined) {
      return [];
    }
    if (Array.isArray(value)) {
      return value;
    }
    var iterable = value !== null && typeof value === "object" && typeof Symbol === "function" &&
      typeof value[Symbol.iterator] === "function";
    return iterable ? Array.from(value) : [value];
  }

  // 2.3.2 defines Element.getAnimations() but not Document's, which pages use to stop every
  // animation on the page. The polyfill keeps all of them on one timeline, so its list is the
  // document's: there is no second timeline to exclude.
  function addDocumentAnimations() {
    if (typeof Document.prototype.getAnimations === "function") {
      return;
    }
    Object.defineProperty(Document.prototype, "getAnimations", {
      configurable: true,
      writable: true,
      value: function getAnimations() {
        var timeline = this.timeline;
        return timeline && typeof timeline.getAnimations === "function" ? timeline.getAnimations() : [];
      }
    });
  }

  // The polyfill's effects lack getComputedTiming(), which pages use to resume an animation
  // where it was (docs.mistral.ai's marquee does when its text changes width). Computed from
  // the effect's timing and its animation's current time, as the spec defines it. Reads the
  // 2.3.2 build's private fields (_timing, _animation, _easingFunction) and activeDuration:
  // recheck them when the polyfill changes.
  function addComputedTiming() {
    var effect = window.KeyframeEffect && window.KeyframeEffect.prototype;
    if (!effect || effect.getComputedTiming) {
      return;
    }
    Object.defineProperty(effect, "getComputedTiming", {
      configurable: true,
      writable: true,
      value: function getComputedTiming() {
        var localTime = this._animation ? this._animation.currentTime : null;
        return computedTiming(this._timing, this.activeDuration, localTime);
      }
    });
  }

  // https://drafts.csswg.org/web-animations-1/#dom-animationeffect-getcomputedtiming
  // Example: duration 1000, iterations Infinity, local time 2500 → progress 0.5, iteration 2.
  function computedTiming(timing, activeDuration, localTime) {
    var endTime = Math.max(timing.delay + activeDuration + timing.endDelay, 0);
    var result = {
      delay: timing.delay,
      endDelay: timing.endDelay,
      fill: timing.fill,
      iterationStart: timing.iterationStart,
      iterations: timing.iterations,
      duration: timing.duration,
      direction: timing.direction,
      easing: timing.easing,
      activeDuration: activeDuration,
      endTime: endTime,
      localTime: localTime,
      progress: null,
      currentIteration: null
    };
    if (localTime === null || localTime === undefined) {
      return result;
    }

    var fillsBackwards = timing.fill === "backwards" || timing.fill === "both";
    var fillsForwards = timing.fill === "forwards" || timing.fill === "both";
    var before = localTime < Math.min(timing.delay, endTime);
    var after = !before && localTime >= Math.min(timing.delay + activeDuration, endTime);
    if ((before && !fillsBackwards) || (after && !fillsForwards)) {
      return result;
    }

    var activeTime = before ? 0 : Math.max(Math.min(localTime - timing.delay, activeDuration), 0);
    var overall = timing.duration === 0
      ? timing.iterationStart + (before ? 0 : timing.iterations)
      : activeTime / timing.duration + timing.iterationStart;
    var simple = overall === Infinity ? timing.iterationStart % 1 : overall % 1;
    var endsIteration = activeTime !== 0 || timing.duration === 0;
    if (simple === 0 && after && timing.iterations !== 0 && endsIteration) {
      simple = 1;
    }
    var iteration = after && timing.iterations === Infinity
      ? Infinity
      : Math.floor(overall) - (simple === 1 ? 1 : 0);

    var forwards = timing.direction === "normal" ||
      (timing.direction === "alternate" && iteration % 2 === 0) ||
      (timing.direction === "alternate-reverse" && iteration % 2 !== 0);
    var directed = forwards ? simple : 1 - simple;
    var ease = timing._easingFunction;
    result.progress = typeof ease === "function" ? ease(directed) : directed;
    result.currentIteration = iteration;
    return result;
  }
})(function () {
  // Outside the strict-mode function above: the polyfill was written for sloppy mode.
  __WEB_ANIMATIONS__
});
