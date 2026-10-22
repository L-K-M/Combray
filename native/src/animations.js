// Combray: Element.animate() for pages, until Servo implements Web Animations.
//
// Servo 0.6.0 has no Element.animate(): its Web Animations interfaces are incomplete stubs behind
// a pref. Pages that call it unconditionally break; docs.mistral.ai shows "Application error"
// when its marquee starts. This script runs in every document before the page's own scripts and
// adds only a stub. The first call installs the web-animations-js polyfill and hands over to it.
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

  Object.defineProperty(Element.prototype, "animate", {
    configurable: true,
    writable: true,
    value: function animate(keyframes, options) {
      if (!installed) {
        installed = true;
        delete Element.prototype.animate;
        installPolyfill();
        addComputedTiming();
      }
      return this.animate(keyframes, options);
    }
  });

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
