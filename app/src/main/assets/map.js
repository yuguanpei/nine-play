'use strict';

window.mapState = 'loading';
window.movingDraw = true;
const sdk = document.createElement('script');
const mapTimeout = setTimeout(() => { window.mapState = 'error'; }, 18000);
sdk.src = 'https://webapi.amap.com/maps?v=2.0&key=' + encodeURIComponent(window.bikeMapKey || '');
sdk.onerror = () => { window.mapState = 'error'; };

sdk.onload = () => {
    try {
        startMap();
    } catch (_) {
        window.mapState = 'error';
    }
};
document.head.appendChild(sdk);

function startMap() {
    const view = document.getElementById('map');
    let nextTurnDistance;
    let night = window.bikeMapDark !== false;
    document.documentElement.style.setProperty('--map-bg', night ? '#121d29' : '#e9eeeb');
    const map = new AMap.Map('map', {
        center: [104, 35],
        // Let the vector renderer orient its labels. Zero pitch keeps the map flat.
        viewMode: '3D',
        pitch: 0,
        pitchEnable: false,
        rotateEnable: true,
        showIndoorMap: false,
        jogEnable: false,
        animateEnable: false,
        touchZoomCenter: 1,
        zoom: 4,
        mapStyle: night ? 'amap://styles/dark' : 'amap://styles/normal',
        resizeEnable: true,
        showBuildingBlock: false,
        features: ['bg', 'road', 'point'],
    });
    function createPin(className, text, zIndex, content = document.createElement('div')) {
        content.className = className;
        content.textContent = text;
        const marker = new AMap.Marker({
            content, anchor: 'center', clickable: false, bubble: true, zIndex,
        });
        marker.hide();
        map.add(marker);
        return marker;
    }

    const riderIcon = document.createElement('div');
    const rider = createPin('rider', '', 120, riderIcon);
    let riderVisible = false, paintedRiderAngle;
    const finish = createPin('finish', '终', 110);
    const pick = createPin('picked', '', 125);
    const routeLine = new AMap.Polyline({
        strokeColor: '#d8ff4f', strokeOpacity: 1, strokeWeight: 6, isOutline: true,
        outlineColor: '#56651e', borderWeight: 2, lineJoin: 'round', lineCap: 'round',
        bubble: true, zIndex: 65,
    });
    routeLine.hide();
    map.add(routeLine);
    let current, target, frame, lastFixAt;
    let lastPaintAt = null, frameScheduledAt = null;
    let movement, lastDraw = 0, positionDuration = 800, cameraTransition, cameraCheck;
    let touching = false, gestureActive = false, markerPosition;
    let route = [];
    let routeDistances = [], routeSegment = 1;
    let paintedProgress = 0, routeTarget, routeMovement, routePaintAt = -Infinity;
    let navigating = false;
    let headingUp = false;
    let phoneHeading, drawnHeading, cameraHeading, headingFresh = false, headingExpiry;
    let stopped = true;
    let browsing = false;
    let browseTimer, releasedAngle;
    let overview = false;
    let overviewFitAngle;
    let bottomInset = 0, sideInset = 0;
    let fitFrame;

    const radians = Math.PI / 180;
    const browseDelay = 15_000;
    const cameraDuration = 800;
    const idleZoom = 17;
    const angleDelta = (from, to) => ((to - from + 180) % 360 + 360) % 360 - 180;

    function displacement(a, b) {
        const scale = 111195 * Math.cos(a[1] * radians);
        return [angleDelta(a[0], b[0]) * scale, (b[1] - a[1]) * 111195];
    }

    function interpolatePoint(from, to, fraction) {
        if (fraction === 1) return to;
        const longitude = from[0] + angleDelta(from[0], to[0]) * fraction;
        return [angleDelta(0, longitude), from[1] + (to[1] - from[1]) * fraction];
    }

    function setViewAngle(angle) {
        map.setRotation((angle % 360 + 360) % 360, true);
        checkCameraAfterUpdate();
    }

    function checkCameraAfterUpdate() {
        if (cameraCheck != null || browsing || overview) return;
        // Verify the applied pose even if the last update was interrupted and no new fix arrives.
        cameraCheck = setTimeout(() => {
            cameraCheck = null;
            if (!browsing && !overview && !document.hidden) scheduleDraw();
        }, 180);
    }

    function desiredViewAngle() {
        return headingUp && cameraHeading != null ? -cameraHeading : 0;
    }

    function smoothPhoneHeading(elapsed) {
        const heading = headingFresh ? phoneHeading : null;
        if (heading == null) return false;
        if (drawnHeading == null) drawnHeading = heading;
        const difference = angleDelta(drawnHeading, heading);
        if (Math.abs(difference) <= .5) {
            drawnHeading = heading;
            return false;
        }
        drawnHeading += difference * (1 - Math.exp(-Math.min(elapsed, 100) / 110));
        return true;
    }

    function smoothCameraHeading(elapsed) {
        if (drawnHeading == null) return false;
        if (cameraHeading == null) cameraHeading = drawnHeading;
        const delta = angleDelta(cameraHeading, drawnHeading);
        if (Math.abs(delta) < 2.5) return false;
        const dt = Math.min(elapsed, 100);
        const change = delta * (1 - Math.exp(-dt / 480));
        const limit = 80 * dt / 1000;
        cameraHeading += Math.max(-limit, Math.min(limit, change));
        return true;
    }

    function paintArrow() {
        // Map rotation is already signed in screen coordinates. The arrow follows the phone in both modes.
        const cameraAngle = map.getRotation();
        const angle = drawnHeading == null ? 0 : angleDelta(0, drawnHeading + cameraAngle);
        if (paintedRiderAngle != null && Math.abs(angleDelta(paintedRiderAngle, angle)) < .05) return;
        paintedRiderAngle = angle;
        riderIcon.style.setProperty('--rider-angle', angle + 'deg');
    }

    function navigationZoom() {
        const near = 50;
        const far = 180;
        const distance = nextTurnDistance ?? far;
        const ease = 1 - Math.max(0, Math.min(1, (distance - near) / (far - near)));
        return 18.25 + .85 * ease;
    }

    function paintCamera(elapsed) {
        if (browsing) return false;
        const desiredZoom = navigating ? navigationZoom() : idleZoom;
        if (cameraTransition) {
            const {from, fromZoom, fromAngle, started, duration, destination} = cameraTransition;
            const progress = Math.max(0, Math.min(1, (lastDraw - started) / duration));
            const ease = progress * progress * (3 - 2 * progress);
            const point = destination ? destination.center : current;
            const zoom = destination ? destination.zoom : desiredZoom;
            if (destination?.angle != null) {
                setViewAngle(fromAngle + angleDelta(fromAngle, destination.angle) * ease);
            }
            const cameraZoom = fromZoom + (zoom - fromZoom) * ease;
            const camera = interpolatePoint(from, point, ease);
            map.setZoomAndCenter(cameraZoom, camera, true);
            if (progress === 1) {
                cameraTransition = null;
                paintMarkers();
                checkCameraAfterUpdate();
            }
            return progress < 1;
        }
        if (!current || overview) return false;
        const zoom = map.getZoom(6);
        const difference = desiredZoom - zoom;
        if (Math.abs(difference) >= .02) {
            const changing = Math.abs(difference) > .035;
            const nextZoom = changing ? zoom + difference * (1 - Math.exp(-Math.min(elapsed, 100) / 480))
                    : desiredZoom;
            map.setZoomAndCenter(nextZoom, current, true);
            checkCameraAfterUpdate();
            return changing;
        }
        const center = map.getCenter();
        if (Math.hypot(...displacement([center.getLng(), center.getLat()], current)) >= .03) {
            map.setCenter(current, true);
            checkCameraAfterUpdate();
        }
        return false;
    }

    function paintMarkers() {
        updateGeographicMarker();
        paintArrow();
    }

    function updateGeographicMarker() {
        if (!current || gestureActive && markerPosition) return;
        if (!markerPosition || markerPosition[0] !== current[0] || markerPosition[1] !== current[1]) {
            markerPosition = current;
            rider.setPosition(current);
        }
        if (!riderVisible) {
            riderVisible = true;
            rider.show();
        }
    }

    function endBrowsing() {
        clearTimeout(browseTimer);
        browsing = false;
        releasedAngle = null;
        gestureActive = false;
    }

    function animateCamera(destination = null) {
        const point = destination ? destination.center : current;
        const zoom = destination ? destination.zoom : (navigating ? navigationZoom() : idleZoom);
        if (!point) return;
        if (cameraTransition) {
            const last = cameraTransition.destination;
            if (!last && !destination) return;
            const angle = map.getRotation();
            if (last && destination && Math.abs(last.zoom - zoom) < .025
                    && Math.abs(angleDelta(last.angle ?? angle, destination.angle ?? angle)) < .5
                    && Math.hypot(...displacement(last.center, point)) <= 1) return;
        }
        const center = map.getCenter();
        const from = [center.getLng(), center.getLat()];
        const fromZoom = map.getZoom();
        const fromAngle = map.getRotation();
        const turning = Math.abs(angleDelta(fromAngle, destination?.angle ?? fromAngle)) >= .15;
        if (turning || Math.abs(fromZoom - zoom) > .025 || Math.hypot(...displacement(from, point)) > 1) {
            const duration = Math.abs(fromZoom - zoom) > 4 ? 1050 : cameraDuration;
            cameraTransition = {from, fromZoom, fromAngle, destination, duration, started: performance.now()};
        } else {
            cameraTransition = null;
            if (destination?.angle != null) setViewAngle(destination.angle);
            map.setZoomAndCenter(zoom, point, true);
            checkCameraAfterUpdate();
        }
        paintMarkers();
        scheduleDraw();
    }

    function focusRider() {
        endBrowsing();
        animateCamera();
        scheduleDraw(); // Rotation can resume even before the first GPS fix.
    }

    function resumeFollowingLater() {
        clearTimeout(browseTimer);
        if (!browsing || touching) return;
        browseTimer = setTimeout(() => {
            if (overview) {
                endBrowsing();
                fitRoute();
            } else {
                focusRider();
            }
        }, browseDelay);
    }

    function beginBrowsing(dragging) {
        if (!browsing) {
            cameraTransition = null;
            browsing = true;
            cancelAnimationFrame(fitFrame);
            paintMarkers();
        }
        if (dragging) gestureActive = true;
        else resumeFollowingLater();
    }

    function endGesture() {
        const wasDragging = gestureActive;
        gestureActive = false;
        if (wasDragging) updateGeographicMarker();
        resumeFollowingLater();
    }

    view.addEventListener('wheel', () => beginBrowsing(false), {passive: true});
    const touchOptions = {passive: true, capture: true};
    view.addEventListener('touchstart', event => {
        if (!touching || event.touches.length >= 2) releasedAngle = null;
        touching = true;
        clearTimeout(browseTimer);
        beginBrowsing(true);
    }, touchOptions);
    view.addEventListener('touchmove', () => {
        if (browsing) scheduleDraw();
    }, touchOptions);
    for (const event of ['touchend', 'touchcancel']) view.addEventListener(event, value => {
        if (browsing && value.touches.length < 2 && releasedAngle == null) releasedAngle = map.getRotation();
        touching = value.touches.length > 0;
        if (!touching) endGesture();
    }, touchOptions);
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            if (touching) {
                touching = false;
                endGesture();
            }
        } else scheduleDraw();
    });

    function prepareRoute(points) {
        route = points;
        routeDistances = [0];
        for (let i = 1; i < points.length; i++) {
            const [a, b] = [points[i - 1], points[i]];
            const east = (b[0] - a[0]) * Math.cos((a[1] + b[1]) * .5 * radians);
            const meters = Math.hypot(east, b[1] - a[1]) * 111195;
            routeDistances.push(routeDistances[i - 1] + meters);
        }
        routeSegment = 1;
        paintedProgress = 0;
        routePaintAt = -Infinity;
        routeTarget = routeMovement = null;
    }

    function paintRoute(progress) {
        if (progress <= paintedProgress || route.length < 2) return;
        paintedProgress = progress;
        while (routeSegment + 1 < route.length && routeDistances[routeSegment] <= progress) routeSegment++;
        const segmentStart = routeDistances[routeSegment - 1];
        const fraction = (progress - segmentStart) / (routeDistances[routeSegment] - segmentStart);
        const a = route[routeSegment - 1], b = route[routeSegment];
        const cut = [a[0] + (b[0] - a[0]) * fraction, a[1] + (b[1] - a[1]) * fraction];
        routeLine.setPath([cut, ...route.slice(routeSegment)]);
    }

    function scheduleDraw() {
        if (frame == null) {
            frameScheduledAt = performance.now();
            frame = requestAnimationFrame(draw);
        }
    }

    function draw(time) {
        frame = null;
        const elapsed = lastDraw ? time - lastDraw : 1000 / 60;
        lastDraw = time;
        lastPaintAt = performance.now();
        if (movement) {
            const {from, to, started, duration} = movement;
            const progress = Math.max(0, Math.min(1, (time - started) / duration));
            current = interpolatePoint(from, to, progress);
            if (progress === 1) movement = null;
            updateGeographicMarker();
        }
        if (routeMovement) {
            const {from, to, started, duration} = routeMovement;
            const fraction = Math.max(0, Math.min(1, (time - started) / duration));
            // Rebuilding a long polyline is independent of the camera's display-rate animation.
            if (fraction === 1 || time - routePaintAt >= 100) {
                paintRoute(from + (to - from) * fraction);
                routePaintAt = time;
            }
            if (fraction === 1) routeMovement = null;
        }
        const updatingHeading = smoothPhoneHeading(elapsed);
        const updatingCameraHeading = smoothCameraHeading(elapsed);
        let turning = false;
        if (browsing && releasedAngle != null && Math.abs(angleDelta(map.getRotation(), releasedAngle)) > .05) {
            setViewAngle(releasedAngle);
        } else if (overview && !browsing) {
            const drift = overviewFitAngle == null ? 0 : angleDelta(overviewFitAngle, desiredViewAngle());
            if (!cameraTransition && Math.abs(drift) > 2) fitRoute();
        } else if (!browsing) {
            const desiredAngle = desiredViewAngle();
            const angle = map.getRotation();
            const delta = angleDelta(angle, desiredAngle);
            if (Math.abs(delta) >= .15) {
                const fraction = 1 - Math.exp(-Math.min(elapsed, 100) / 170);
                turning = Math.abs(delta) > .5;
                setViewAngle(turning ? angle + delta * fraction : desiredAngle);
            }
        }
        const zooming = paintCamera(elapsed);
        if (current) paintArrow();
        if (movement || turning || routeMovement || zooming || updatingHeading || updatingCameraHeading) scheduleDraw();
        else lastDraw = 0;
    }

    function setNavigating(enabled) {
        if (navigating === enabled) return;
        navigating = enabled;
        if (overview) setOverview(false);
        focusRider();
    }

    function fitRoute() {
        if (browsing) return;
        cancelAnimationFrame(fitFrame);
        fitFrame = requestAnimationFrame(() => {
            if (!overview || !navigating || browsing || route.length < 2) return;
            fitVisibleRoute();
        });
    }

    function fitVisibleRoute() {
        const angle = desiredViewAngle();
        const delta = angleDelta(map.getRotation(), angle) * radians;
        const cos = Math.cos(delta), sin = Math.sin(delta);
        const centerX = view.clientWidth / 2, centerY = view.clientHeight / 2;
        // Project into the target heading without rotating the visible map before the animation.
        function project(point) {
            const screen = map.lngLatToContainer(new AMap.LngLat(...point));
            const x = screen.x - centerX, y = screen.y - centerY;
            return {x: centerX + x * cos - y * sin, y: centerY + x * sin + y * cos};
        }
        const origin = project(current || route[0]);
        let left = origin.x, right = origin.x, top = origin.y, bottom = origin.y;
        for (const point of route) {
            const {x, y} = project(point);
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
        }
        // The WebView extends below the visible pane to keep the rider at two-thirds height.
        const safeLeft = sideInset + 24, safeRight = view.clientWidth - sideInset - 68;
        const safeTop = 64, safeBottom = document.body.clientHeight - bottomInset - 22;
        if (safeRight <= safeLeft || safeBottom <= safeTop) return;
        const scale = Math.min((safeRight - safeLeft) / Math.max(1, right - left),
            (safeBottom - safeTop) / Math.max(1, bottom - top));
        const fromZoom = map.getZoom();
        const zoom = Math.max(2, Math.min(17, fromZoom + Math.log2(scale)));
        const appliedScale = Math.pow(2, zoom - fromZoom);
        const x = (left + right) / 2 - ((safeLeft + safeRight - view.clientWidth) / 2) / appliedScale;
        const y = (top + bottom) / 2 - ((safeTop + safeBottom - view.clientHeight) / 2) / appliedScale;
        const dx = x - centerX, dy = y - centerY;
        const center = map.containerToLngLat(new AMap.Pixel(
            centerX + dx * cos + dy * sin, centerY - dx * sin + dy * cos));
        if (center) {
            overviewFitAngle = angle;
            animateCamera({zoom, angle, center: [center.getLng(), center.getLat()]});
        }
    }

    function setOverview(enabled, forward = headingUp) {
        if (overview === enabled || (enabled && !navigating)) return;
        headingUp = forward;
        overview = enabled;
        overviewFitAngle = null;
        endBrowsing();
        if (overview) {
            cameraTransition = null;
        }
        paintMarkers();
        if (enabled) {
            fitRoute();
            scheduleDraw();
        }
        else {
            cancelAnimationFrame(fitFrame);
            if (navigating) focusRider();
        }
    }

    map.on('complete', () => {
        clearTimeout(mapTimeout);
        window.mapState = 'ready';
    });
    map.on('rotatechange', () => {
        if (browsing) scheduleDraw();
    });
    map.on('dblclick', () => beginBrowsing(false));
    map.on('resize', () => {
        if (overview) fitRoute();
        else scheduleDraw();
    });

    window.bikeMap = {
        diagnostics() {
            const center = map.getCenter();
            const camera = [center.getLng(), center.getLat()];
            const actual = rider.getPosition?.();
            const marker = actual ? [actual.getLng(), actual.getLat()] : markerPosition;
            return {
                jsWallMs: Date.now(), jsMs: performance.now(), hidden: document.hidden,
                current, target, marker, camera, zoom: map.getZoom(), rotation: map.getRotation(),
                desiredRotation: desiredViewAngle(), phoneHeading, drawnHeading, cameraHeading, headingFresh,
                headingUp, navigating, stopped, browsing, overview, gestureActive, touching,
                cameraTransition: !!cameraTransition, moving: !!movement, frameQueued: frame != null,
                lastDrawAgeMs: lastPaintAt == null ? null : performance.now() - lastPaintAt,
                frameWaitMs: frame != null && frameScheduledAt != null ? performance.now() - frameScheduledAt : 0,
                lastFixAgeMs: lastFixAt == null ? null : performance.now() - lastFixAt,
                markerErrorM: marker && current ? Math.hypot(...displacement(marker, current)) : null,
                cameraErrorM: current ? Math.hypot(...displacement(camera, current)) : null,
                routeProgressM: paintedProgress, routeTargetM: routeTarget, nextTurnDistance,
                routeTotalM: routeDistances.length ? routeDistances[routeDistances.length - 1] : 0,
                viewWidth: view.clientWidth, viewHeight: view.clientHeight, bottomInset, sideInset,
            };
        },
        pointAt(x, y) {
            if (window.mapState !== 'ready') return null;
            const rect = view.getBoundingClientRect();
            const point = map.containerToLngLat(new AMap.Pixel(
                x / window.devicePixelRatio - rect.left, y / window.devicePixelRatio - rect.top));
            return point ? [point.getLng(), point.getLat()] : null;
        },
        showPickedPoint(point) {
            if (!point) pick.hide();
            else {
                pick.setPosition(point);
                pick.show();
            }
        },
        showRoute(points, active) {
            prepareRoute(points);
            nextTurnDistance = null;
            if (route.length > 1) {
                routeLine.setPath(route);
                routeLine.show();
                finish.setPosition(route[route.length - 1]);
                finish.show();
            } else {
                routeLine.hide();
                finish.hide();
            }
            setNavigating(active && route.length > 1);
            if (overview) fitRoute();
        },
        trimRoute(progress) {
            if (!navigating || !Number.isFinite(progress)) return;
            const next = Math.min(progress, routeDistances[routeDistances.length - 1]);
            if (routeTarget == null) paintRoute(next);
            else {
                if (next <= routeTarget) return;
                routeMovement = {from: paintedProgress, to: next, started: performance.now(), duration: positionDuration};
                scheduleDraw();
            }
            routeTarget = next;
        },
        setPosition(point, speed, accuracy) {
            const now = performance.now();
            const elapsed = lastFixAt == null ? 1000 : now - lastFixAt;
            lastFixAt = now;
            positionDuration = Math.max(220, Math.min(1450, elapsed * 1.04));
            const wasStopped = stopped;
            if (speed >= 0) stopped = speed < .55;
            if (!current) {
                current = target = point;
                if (!overview && !browsing) focusRider();
                else paintMarkers();
            } else {
                const distance = Math.hypot(...displacement(target, point));
                const stoppedRadius = wasStopped ? Math.max(5, Math.min(25, (accuracy || 0) * .7)) : 2.5;
                if (distance >= (stopped ? stoppedRadius : .02)) {
                    target = point;
                    if (document.hidden) {
                        movement = null;
                        current = target;
                        updateGeographicMarker();
                    } else {
                        // Keep the pan moving through the next expected fix.
                        const duration = distance > 130 || elapsed > 5000 ? 1050 : positionDuration;
                        movement = {from: current, to: point, started: now, duration};
                    }
                }
            }
            scheduleDraw();
        },
        setHeadingUp(enabled) {
            if (headingUp === enabled) return;
            headingUp = enabled;
            touching = false;
            paintMarkers();
            if (overview) {
                fitRoute();
            } else focusRider();
            scheduleDraw();
        },
        previewArea(point) {
            if (!current && !browsing) animateCamera({center: point, zoom: 17});
        },
        setPhoneHeading(degrees) {
            if (!Number.isFinite(degrees)) {
                phoneHeading = null;
                headingFresh = false;
                clearTimeout(headingExpiry);
                if (current) paintArrow();
                return;
            }
            phoneHeading = degrees;
            if (drawnHeading == null) drawnHeading = degrees;
            headingFresh = true;
            clearTimeout(headingExpiry);
            headingExpiry = setTimeout(() => {
                headingFresh = false;
                if (current) paintArrow();
                scheduleDraw();
            }, 3500);
            scheduleDraw();
        },
        setOverview,
        setViewportInsets(bottom, side) {
            if (bottomInset === bottom && sideInset === side) return;
            bottomInset = bottom;
            sideInset = side;
            document.documentElement.style.setProperty('--trip-inset', bottom + 'px');
            if (overview) fitRoute();
        },
        setTheme(color, dark) {
            document.documentElement.style.setProperty('--accent', color);
            document.documentElement.style.setProperty('--map-bg', dark ? '#121d29' : '#e9eeeb');
            routeLine.setOptions({strokeColor: color, outlineColor: dark ? '#122632' : '#ffffff'});
            if (night !== dark) {
                night = dark;
                map.setMapStyle(dark ? 'amap://styles/dark' : 'amap://styles/normal');
            }
        },
        setNextTurnDistance(meters) {
            nextTurnDistance = Number.isFinite(meters) ? meters : null;
            if (navigating) scheduleDraw();
        },
        recenter() {
            if (!overview) focusRider();
        },
    };
}
