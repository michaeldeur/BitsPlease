// ===================================================================
//  Facial recognition with a plug-and-play AI model (face-api.js)
// ===================================================================
//  The library is loaded from a CDN in the HTML:
//    <script src="https://cdn.jsdelivr.net/npm/@vladmandic/face-api@1.7.12/dist/face-api.js"></script>
//
//  Three pre-trained neural networks are used:
//    1. SSD MobileNet v1  – finds faces in the webcam frame
//    2. Face Landmark 68  – finds eyes, nose, mouth (68 points)
//    3. Face Recognition  – ResNet-34 that turns a face into 128 numbers ("descriptor")
//
//  Only the 128 numbers are sent to the server – never the camera image.
//  The Java backend compares them with the enrolled ones (Euclidean distance).
//
//  To run offline, copy the model files into src/main/resources/static/models/
//  and change FACE_MODEL_URL to '/models/'.
// ===================================================================

const FACE_MODEL_URL = 'https://cdn.jsdelivr.net/npm/@vladmandic/face-api@1.7.12/model/';

const FaceAuth = (() => {
  let modelsPromise = null;
  let stream = null;

  function loadModels() {
    if (typeof faceapi === 'undefined') {
      return Promise.reject(new Error('Face recognition library failed to load. Check your internet connection.'));
    }
    if (!modelsPromise) {
      modelsPromise = Promise.all([
        faceapi.nets.ssdMobilenetv1.loadFromUri(FACE_MODEL_URL),
        faceapi.nets.faceLandmark68Net.loadFromUri(FACE_MODEL_URL),
        faceapi.nets.faceRecognitionNet.loadFromUri(FACE_MODEL_URL),
      ]);
    }
    return modelsPromise;
  }

  async function startCamera(video) {
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error('This browser cannot access a webcam. Use Chrome, Edge or Firefox over http://localhost or https.');
    }
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' },
        audio: false,
      });
    } catch (e) {
      if (e.name === 'NotAllowedError') throw new Error('Camera permission was denied. Allow camera access and reload.');
      if (e.name === 'NotFoundError') throw new Error('No webcam was found.');
      throw e;
    }
    video.srcObject = stream;
    await video.play();
    return stream;
  }

  function stopCamera(video) {
    stream?.getTracks().forEach(t => t.stop());
    stream = null;
    if (video) video.srcObject = null;
  }

  /** Detects exactly one face. Returns { detection, landmarks, descriptor } or throws a friendly error. */
  async function detectOne(video) {
    const results = await faceapi
      .detectAllFaces(video, new faceapi.SsdMobilenetv1Options({ minConfidence: 0.6 }))
      .withFaceLandmarks()
      .withFaceDescriptors();
    if (results.length === 0) throw new Error('No face detected — centre your face in the oval.');
    if (results.length > 1) throw new Error('More than one face detected — only you should be in frame.');
    return results[0];
  }

  /** Draws the detection box + landmarks on a canvas laid over the video. */
  function draw(canvas, video, result) {
    const dims = faceapi.matchDimensions(canvas, video, true);
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (!result) return;
    const resized = faceapi.resizeResults(result, dims);
    faceapi.draw.drawDetections(canvas, resized);
    faceapi.draw.drawFaceLandmarks(canvas, resized);
  }

  /** How far the head is turned: ~0 looking straight, bigger when turned sideways. */
  function headTurn(landmarks) {
    const nose = landmarks.getNose()[3];
    const le = landmarks.getLeftEye()[0];
    const re = landmarks.getRightEye()[3];
    const midX = (le.x + re.x) / 2;
    const eyeDist = Math.hypot(re.x - le.x, re.y - le.y) || 1;
    return (nose.x - midX) / eyeDist;
  }

  /** Averages several descriptors into one (more stable than a single frame). */
  function average(descriptors) {
    const out = new Array(128).fill(0);
    descriptors.forEach(d => d.forEach((v, i) => { out[i] += v / descriptors.length; }));
    return out;
  }

  const sleep = ms => new Promise(r => setTimeout(r, ms));

  /**
   * Simple liveness check + capture for login:
   *   1. user looks straight  → capture
   *   2. user turns head to either side → proves it's not a still photo
   *   3. user looks straight again → capture
   * Returns the averaged 128-number descriptor.
   */
  async function verifyWithLiveness(video, canvas, prompt, timeoutMs = 20000) {
    const start = Date.now();
    const frontal = [];
    let phase = 'front1';
    prompt('Look straight at the camera');

    while (Date.now() - start < timeoutMs) {
      let r;
      try {
        r = await detectOne(video);
      } catch (e) {
        draw(canvas, video, null);
        prompt(e.message);
        await sleep(150);
        continue;
      }
      draw(canvas, video, r);
      const turn = Math.abs(headTurn(r.landmarks));

      if (phase === 'front1' && turn < 0.12) {
        frontal.push(Array.from(r.descriptor));
        if (frontal.length >= 2) { phase = 'turn'; prompt('Now slowly turn your head to one side ↔'); }
      } else if (phase === 'turn' && turn > 0.22) {
        phase = 'front2'; prompt('Great — look back at the camera');
      } else if (phase === 'front2' && turn < 0.12) {
        frontal.push(Array.from(r.descriptor));
        if (frontal.length >= 4) {
          prompt('Checking…');
          return average(frontal);
        }
      }
      await sleep(120);
    }
    throw new Error('Timed out. Make sure your face is well lit and try again.');
  }

  /** Enrollment: captures `count` samples while asking for slightly different poses. */
  async function enroll(video, canvas, prompt, onSample, count = 5) {
    const poses = [
      'Look straight at the camera',
      'Tilt your head slightly to one side',
      'Tilt your head slightly to the other side',
      'Give a small smile',
      'Look straight again',
    ];
    const samples = [];
    while (samples.length < count) {
      prompt(`${poses[samples.length % poses.length]} (${samples.length + 1}/${count})`);
      await sleep(1200); // give the user time to move
      let r = null;
      for (let tries = 0; tries < 25 && !r; tries++) {
        try { r = await detectOne(video); } catch (e) { prompt(e.message); await sleep(200); }
      }
      if (!r) throw new Error('Could not see your face clearly. Improve lighting and try again.');
      draw(canvas, video, r);
      samples.push(Array.from(r.descriptor));
      onSample(samples.length);
    }
    prompt('All samples captured ✔');
    return samples;
  }

  return { loadModels, startCamera, stopCamera, verifyWithLiveness, enroll, draw };
})();
