const stage = document.querySelector('.demo-stage');
const cameraPreview = document.querySelector('#cameraPreview');
const landmarkOverlay = document.querySelector('#landmarkOverlay');
const overlayContext = landmarkOverlay.getContext('2d');
const startCameraButton = document.querySelector('#startCamera');
const cameraStatus = document.querySelector('#cameraStatus');
const cursor = document.querySelector('#gestureCursor');
const puck = document.querySelector('#dragPuck');
const missionStatus = document.querySelector('#missionStatus');
const tiles = [...document.querySelectorAll('.demo-tile')];
const dropRing = document.querySelector('#dropRing');

const WASM_ROOT = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/wasm';
const MODEL_URL = 'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task';
const MEDIAPIPE_MODULE_URL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/+esm';
const MOTION_SENSITIVITY = 1.65;
const PINCH_THRESHOLD = 0.22;
const THUMB_EXTENSION_THRESHOLD = 1.2;
const FINGER_FOLD_THRESHOLD = 0.88;
const DRAG_HYSTERESIS_FRAMES = 3;
const HAND_CONNECTIONS = [
    [0, 1], [1, 2], [2, 3], [3, 4], [0, 5], [5, 6], [6, 7], [7, 8],
    [0, 9], [9, 10], [10, 11], [11, 12], [0, 13], [13, 14], [14, 15],
    [15, 16], [0, 17], [17, 18], [18, 19], [19, 20]
];

let stream = null;
let handLandmarker = null;
let visionModulePromise = null;
let animationFrame = 0;
let lastVideoTime = -1;
let previousWrist = null;
let cursorPosition = { x: 0.5, y: 0.58 };
let clickLatched = false;
let dragFrameCount = 0;
let dragging = false;
let noHandFrames = 0;
let selectedTiles = 0;

function clamp(value, min = 0, max = 1) {
    return Math.min(max, Math.max(min, value));
}

function distance(first, second) {
    return Math.hypot(first.x - second.x, first.y - second.y);
}

async function createHandLandmarker() {
    if (!visionModulePromise) visionModulePromise = import(MEDIAPIPE_MODULE_URL);
    const { FilesetResolver, HandLandmarker } = await visionModulePromise;
    const vision = await FilesetResolver.forVisionTasks(WASM_ROOT);
    return HandLandmarker.createFromOptions(vision, {
        baseOptions: { modelAssetPath: MODEL_URL },
        runningMode: 'VIDEO',
        numHands: 1,
        minHandDetectionConfidence: 0.55,
        minHandPresenceConfidence: 0.55,
        minTrackingConfidence: 0.55
    });
}

async function startCamera() {
    if (!navigator.mediaDevices?.getUserMedia) {
        cameraStatus.textContent = 'Open this page with localhost or HTTPS to use the camera';
        return;
    }
    if (stream) return;

    startCameraButton.disabled = true;
    startCameraButton.textContent = 'Requesting camera…';
    cameraStatus.textContent = 'Requesting camera permission';

    try {
        stream = await navigator.mediaDevices.getUserMedia({
            video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } },
            audio: false
        });
        cameraPreview.srcObject = stream;
        await cameraPreview.play();
        stage.classList.add('camera-active');
        startCameraButton.textContent = 'Loading HandFree engine…';
        cameraStatus.textContent = 'Camera active · loading gesture engine';
        handLandmarker = await createHandLandmarker();
        cameraStatus.textContent = 'HandFree engine active · local only';
        resizeOverlay();
        renderLoop();
    } catch (error) {
        stream?.getTracks().forEach((track) => track.stop());
        stream = null;
        stage.classList.remove('camera-active');
        cameraStatus.textContent = error.name === 'NotAllowedError'
            ? 'Camera permission was denied. Allow it in the browser address bar and try again.'
            : 'Camera or gesture model could not start. Check your connection and reload.';
        startCameraButton.disabled = false;
        startCameraButton.textContent = 'Start camera';
    }
}

function resizeOverlay() {
    const bounds = stage.getBoundingClientRect();
    const scale = window.devicePixelRatio || 1;
    landmarkOverlay.width = Math.round(bounds.width * scale);
    landmarkOverlay.height = Math.round(bounds.height * scale);
    overlayContext.setTransform(scale, 0, 0, scale, 0, 0);
}

function renderLoop() {
    if (!handLandmarker || !stream) return;
    if (cameraPreview.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA && cameraPreview.currentTime !== lastVideoTime) {
        const result = handLandmarker.detectForVideo(cameraPreview, performance.now());
        const landmarks = result.landmarks?.[0];
        drawLandmarks(landmarks);
        if (landmarks) processLandmarks(landmarks);
        else processNoHand();
        lastVideoTime = cameraPreview.currentTime;
    }
    animationFrame = requestAnimationFrame(renderLoop);
}

function drawLandmarks(landmarks) {
    const bounds = stage.getBoundingClientRect();
    overlayContext.clearRect(0, 0, bounds.width, bounds.height);
    if (!landmarks) return;

    const pointAt = (point) => ({ x: (1 - point.x) * bounds.width, y: point.y * bounds.height });
    overlayContext.lineWidth = 2;
    overlayContext.strokeStyle = 'rgba(0, 229, 255, 0.78)';
    overlayContext.fillStyle = '#8b82ff';
    HAND_CONNECTIONS.forEach(([start, end]) => {
        const first = pointAt(landmarks[start]);
        const second = pointAt(landmarks[end]);
        overlayContext.beginPath();
        overlayContext.moveTo(first.x, first.y);
        overlayContext.lineTo(second.x, second.y);
        overlayContext.stroke();
    });
    landmarks.forEach((point) => {
        const projected = pointAt(point);
        overlayContext.beginPath();
        overlayContext.arc(projected.x, projected.y, 3, 0, Math.PI * 2);
        overlayContext.fill();
    });
}

function processLandmarks(landmarks) {
    noHandFrames = 0;
    const wrist = { x: 1 - landmarks[0].x, y: landmarks[0].y };
    const handScale = distance(landmarks[0], landmarks[9]);
    if (handScale < 0.055) return;

    if (previousWrist) {
        cursorPosition.x = clamp(cursorPosition.x + (wrist.x - previousWrist.x) * MOTION_SENSITIVITY);
        cursorPosition.y = clamp(cursorPosition.y + (wrist.y - previousWrist.y) * MOTION_SENSITIVITY);
    }
    previousWrist = wrist;
    positionCursor();

    const pinchDistance = distance(landmarks[4], landmarks[8]) / handScale;
    const thumbExtension = distance(landmarks[4], landmarks[0]) / handScale;
    const fingersFolded = [[8, 5], [12, 9], [16, 13], [20, 17]].every(([tip, mcp]) => distance(landmarks[tip], landmarks[mcp]) / handScale <= FINGER_FOLD_THRESHOLD);
    const dragPose = thumbExtension >= THUMB_EXTENSION_THRESHOLD && fingersFolded;

    if (dragPose) {
        dragFrameCount = Math.min(DRAG_HYSTERESIS_FRAMES, dragFrameCount + 1);
        clickLatched = false;
        if (dragFrameCount >= DRAG_HYSTERESIS_FRAMES) {
            dragging = true;
            setRuntimeStatus('Drag pose detected');
            cursor.classList.remove('click');
            cursor.classList.add('drag');
            puck.classList.add('dragging');
            movePuck();
        }
        return;
    }

    dragFrameCount = 0;
    if (dragging) releasePuck();
    cursor.classList.remove('drag');

    if (pinchDistance <= PINCH_THRESHOLD) {
        setRuntimeStatus('Pinch detected');
        cursor.classList.add('click');
        if (!clickLatched) {
            activateTileAtCursor();
            clickLatched = true;
        }
    } else {
        setRuntimeStatus('Tracking hand');
        cursor.classList.remove('click');
        clickLatched = false;
    }
}

function processNoHand() {
    noHandFrames += 1;
    if (noHandFrames < DRAG_HYSTERESIS_FRAMES) return;
    previousWrist = null;
    setRuntimeStatus('Looking for hand');
    if (dragging) releasePuck();
    cursor.classList.remove('click', 'drag');
}

function setRuntimeStatus(status) {
    if (cameraStatus.textContent !== status) cameraStatus.textContent = status;
}

function positionCursor() {
    cursor.style.left = `${cursorPosition.x * 100}%`;
    cursor.style.top = `${cursorPosition.y * 100}%`;
}

function activateTileAtCursor() {
    const stageBounds = stage.getBoundingClientRect();
    const cursorX = stageBounds.left + cursorPosition.x * stageBounds.width;
    const cursorY = stageBounds.top + cursorPosition.y * stageBounds.height;
    const tile = tiles.find((candidate) => {
        const bounds = candidate.getBoundingClientRect();
        return cursorX >= bounds.left && cursorX <= bounds.right && cursorY >= bounds.top && cursorY <= bounds.bottom;
    });
    if (!tile || tile.classList.contains('active')) return;
    tile.classList.add('active');
    selectedTiles += 1;
    updateMission();
}

function movePuck() {
    puck.style.left = `${cursorPosition.x * 100}%`;
    puck.style.top = `${cursorPosition.y * 100}%`;
}

function releasePuck() {
    dragging = false;
    puck.classList.remove('dragging');
    if (selectedTiles !== tiles.length) return;
    const puckBounds = puck.getBoundingClientRect();
    const ringBounds = dropRing.getBoundingClientRect();
    const puckCenter = { x: puckBounds.left + puckBounds.width / 2, y: puckBounds.top + puckBounds.height / 2 };
    const ringCenter = { x: ringBounds.left + ringBounds.width / 2, y: ringBounds.top + ringBounds.height / 2 };
    if (distance(puckCenter, ringCenter) > ringBounds.width * 0.55) return;
    puck.style.left = `${((ringCenter.x - stage.getBoundingClientRect().left) / stage.getBoundingClientRect().width) * 100}%`;
    puck.style.top = `${((ringCenter.y - stage.getBoundingClientRect().top) / stage.getBoundingClientRect().height) * 100}%`;
    puck.classList.add('complete');
    dropRing.classList.add('complete');
    dropRing.textContent = 'DONE';
    missionStatus.textContent = 'Mission complete';
}

function updateMission() {
    missionStatus.textContent = `${selectedTiles} of 3 tiles selected`;
    if (selectedTiles === tiles.length) {
        missionStatus.textContent = 'Tiles complete · drag target unlocked';
    }
}

startCameraButton.addEventListener('click', startCamera);
window.addEventListener('resize', resizeOverlay);
window.addEventListener('beforeunload', () => {
    cancelAnimationFrame(animationFrame);
    handLandmarker?.close();
    stream?.getTracks().forEach((track) => track.stop());
});
