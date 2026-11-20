'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync(new URL('../app/src/main/assets/map.js', `file://${__filename}`), 'utf8');

function harness(refreshHz = 30) {
    let now = 0, serial = 0, map;
    const frames = new Map(), timers = new Map(), markers = [], events = new Map();
    const element = () => ({style: {values: {}, setProperty(name, value) { this.values[name] = value; }}, addEventListener() {},
        clientWidth: 800, clientHeight: 600});
    const view = element();
    view.addEventListener = (name, callback) => events.set(name, callback);
    const document = {createElement: element, head: {appendChild() {}}, documentElement: element(),
        body: {clientHeight: 450}, getElementById: () => view, addEventListener() {}, hidden: false};
    class MapMock {
        constructor(_, options) {
            map = this;
            this.center = options.center;
            this.zoom = options.zoom;
            this.rotation = 0;
        }
        add() {}
        on(name, callback) { events.set(name, callback); }
        getCenter() { return {getLng: () => this.center[0], getLat: () => this.center[1]}; }
        setRotation(angle) { this.rotation = angle; events.get('rotatechange')?.(); }
        getRotation() { return this.rotation; }
        getZoom() { return this.zoom; }
        setCenter(point) { this.center = [...point]; }
        setZoomAndCenter(zoom, point) { this.zoom = zoom; this.setCenter(point); }
        lngLatToContainer(lnglat) { return {x: lnglat.lng, y: lnglat.lat}; }
        containerToLngLat(pixel) { return {getLng: () => pixel.x, getLat: () => pixel.y}; }
    }
    class Marker {
        constructor(options) { this.content = options.content; markers.push(this); }
        hide() {}
        show() {}
        setPosition(point) { this.position = [...point]; }
    }
    class Polyline { hide() {} show() {} setPath() {} setOptions() {} }
    const context = {window: {}, document, AMap: {Map: MapMock, Marker, Polyline,
        LngLat: class { constructor(lng, lat) { this.lng = lng; this.lat = lat; } },
        Pixel: class { constructor(x, y) { this.x = x; this.y = y; } }}, performance: {now: () => now},
        setTimeout(fn, delay) { const id = ++serial; timers.set(id, {fn, at: now + delay}); return id; },
        clearTimeout(id) { timers.delete(id); },
        requestAnimationFrame(fn) { const id = ++serial; frames.set(id, fn); return id; },
        cancelAnimationFrame(id) { frames.delete(id); }};
    vm.runInNewContext(source, context);
    vm.runInNewContext('startMap()', context);
    function advance(milliseconds, verify) {
        const end = now + milliseconds;
        while (now < end) {
            now += 1000 / refreshHz;
            if (end - now < 0.000001) now = end;
            for (const [id, timer] of [...timers]) if (timer.at <= now) { timers.delete(id); timer.fn(); }
            const batch = [...frames];
            frames.clear();
            for (const [, callback] of batch) callback(now);
            if (verify) verify();
        }
    }
    return {api: context.window.bikeMap, advance, map, rider: markers[0], document, events};
}

for (const headingUp of [false, true]) {
    const h = harness();
    h.api.setPhoneHeading(90);
    h.api.setHeadingUp(headingUp);
    h.api.setPosition([121.4, 31.2], 10, 5);
    h.advance(2000);
    h.api.setPosition([121.401, 31.2], 10, 5);
    h.advance(3000, () => assert.deepEqual(h.rider.position, h.map.center));
    assert.deepEqual(h.rider.position, [121.401, 31.2]);
    const evidence = h.api.diagnostics();
    assert.equal(evidence.headingUp, headingUp);
    assert.equal(evidence.markerErrorM, 0);
    assert.equal(evidence.cameraErrorM, 0);
    assert.ok(Math.abs(evidence.jsWallMs - Date.now()) < 1000);
    assert.equal(evidence.hidden, false);
    // Heading changes and changing navigation zoom must not detach the rider from the camera.
    h.api.showRoute([[121.401, 31.2], [121.402, 31.2]], true);
    h.advance(2000);
    h.api.setPosition([121.4015, 31.2], 10, 5);
    h.api.setPhoneHeading(179);
    h.api.setNextTurnDistance(10);
    h.advance(2000, () => assert.deepEqual(h.rider.position, h.map.center));
    // Free browsing must still keep the marker on its geographic position.
    h.events.get('wheel')();
    h.map.setCenter([121.5, 31.3]);
    h.api.setPosition([121.4017, 31.2], 0, 5);
    h.advance(2000);
    assert.deepEqual(h.map.center, [121.5, 31.3]);
    assert.deepEqual(h.rider.position, [121.4017, 31.2]);
    assert.equal(h.api.diagnostics().browsing, true);
    assert.equal(h.api.diagnostics().markerErrorM, 0);
    assert.ok(h.api.diagnostics().cameraErrorM > 100);
    h.api.recenter();
    h.advance(2000);
    assert.deepEqual(h.rider.position, h.map.center);
    h.document.hidden = true;
    h.api.setPosition([121.402, 31.2], 10, 5);
    assert.deepEqual(h.rider.position, [121.402, 31.2]);
    h.document.hidden = false;
    h.advance(2000);
    assert.deepEqual(h.rider.position, h.map.center);
}
console.log('Map following: passed (north up, heading up, navigation, browsing, recenter, background)');

// A new fix, heading, first GPS fix or partial finger release must not reclaim a manual view.
for (const headingUp of [false, true]) {
    for (const navigating of [false, true]) {
        const h = harness();
        h.api.setHeadingUp(headingUp);
        h.api.setPhoneHeading(90);
        if (navigating) h.api.showRoute([[121, 31], [122, 32]], true);
        h.events.get('touchstart')({touches: [{}, {}]});
        h.map.setRotation(73);
        h.map.setCenter([122, 31]);
        h.api.setPosition([121, 31], 8, 4);
        h.api.setPosition([121.002, 31], 9, 4);
        h.events.get('touchend')({touches: [{}]});
        h.map.setRotation(30); // SDK motion can outlive the two-finger gesture.
        h.advance(31000);
        assert.equal(h.map.rotation, 73);
        h.events.get('touchend')({touches: []});
        h.map.setRotation(0);
        h.advance(14000);
        h.api.setPosition([121.004, 31], 10, 4);
        h.api.setPhoneHeading(120);
        h.advance(500);
        assert.equal(h.map.rotation, 73);
        assert.deepEqual(h.map.center, [122, 31]);
        h.events.get('touchstart')({touches: [{}]});
        h.events.get('touchcancel')({touches: []});
        h.advance(14900);
        assert.equal(h.api.diagnostics().browsing, true);
        assert.equal(h.map.rotation, 73);
        h.advance(2600);
        assert.equal(h.api.diagnostics().browsing, false);
        assert.ok(Math.abs(h.map.rotation - (headingUp ? 240 : 0)) < 3);
        assert.deepEqual(h.map.center, [121.004, 31]);
    }
}
console.log('Manual rotation: holds until 15 seconds after last touch in both modes, with and without navigation');

// A single corrected heading drives both the marker and camera, including north wrap.
{
    const h = harness();
    const delta = (a, b) => ((b - a + 180) % 360 + 360) % 360 - 180;
    h.api.setHeadingUp(true);
    h.api.setPhoneHeading(90);
    h.api.setPosition([121.4, 31.2], 8, 4);
    h.advance(5000);
    assert.ok(Math.abs(delta(h.map.rotation, 270)) < 3);
    assert.ok(Math.abs(parseFloat(h.rider.content.style.values['--rider-angle'])) < 3);
    h.api.setHeadingUp(false);
    h.advance(3000);
    assert.ok(Math.abs(delta(h.map.rotation, 0)) < .5);
    assert.ok(Math.abs(delta(parseFloat(h.rider.content.style.values['--rider-angle']), 90)) < .5);
    h.api.setHeadingUp(true);
    h.api.setPhoneHeading(359);
    h.advance(5000);
    const before = h.map.rotation;
    h.api.setPhoneHeading(1);
    h.advance(3000, () => assert.ok(Math.abs(delta(before, h.map.rotation)) < 6));
}
console.log('Corrected direction: passed (camera/arrow alignment and north wrap)');

{
    const h = harness(60);
    h.api.setHeadingUp(true);
    h.api.setPhoneHeading(0);
    h.api.setPosition([121, 31], 8, 4);
    h.advance(2000);
    h.api.setPhoneHeading(120);
    let previous = h.map.rotation, changed = 0;
    h.advance(1000, () => {
        if (h.map.rotation !== previous) changed++;
        previous = h.map.rotation;
    });
    assert.ok(changed >= 55, 'camera rotation must use available display frames');
    console.log('Simulated 60 Hz rotation: ' + changed + ' angle updates in one second');
}
