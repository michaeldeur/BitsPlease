# FaceAuth — Multi-Factor Authentication Website (Group Project)

A website where every login requires **three factors**:

| Factor | Type | How it works |
|---|---|---|
| 1. Password | something you **know** | BCrypt-hashed and stored in the SQL database |
| 2. Authenticator code | something you **have** | 6-digit TOTP code (RFC 6238) from Google/Microsoft Authenticator or Authy |
| 3. Face scan | something you **are** | Webcam + pre-trained AI model (face-api.js) → 128-number face descriptor matched on the Java server |

**Tech used:** Java 17 (Spring Boot) · SQL (H2 by default, MySQL optional) · JSON REST API · HTML · CSS · JavaScript · face-api.js AI models

---

## 1. Run it

### Easiest: one command
```bash
cd faceauth-mfa
./start.sh
```
`start.sh` checks for Java 17+ and Maven. If either is missing, it downloads it into `./.tools/` (no admin rights needed). It then downloads all the Java libraries, compiles the project and starts the site at **http://localhost:8080**.

Options: `./start.sh --test` (run tests first), `--mysql` (use MySQL), `--reset-db` (wipe the local database), `PORT=9090 ./start.sh`.

Works on macOS, Linux and Windows (run it from **Git Bash** or **WSL**). If you get "permission denied", run `chmod +x start.sh` first.

> The webcam only works on `http://localhost` or `https://`. If a teammate opens the site from another computer by IP address, the browser will block the camera; use HTTPS (see section 8) or run it locally.

### Manual
Requirements: Java 17+ (JDK), Maven 3.8+, a webcam, and Chrome/Edge/Firefox.
```bash
mvn spring-boot:run
```

**Run tests:** `mvn test`

**Use MySQL instead of H2:**
```sql
CREATE DATABASE faceauth;
```
Edit `src/main/resources/application-mysql.properties` with your username and password, then:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

**Look inside the database (H2):** open http://localhost:8080/h2-console
JDBC URL `jdbc:h2:file:./data/faceauth;MODE=MySQL;DATABASE_TO_LOWER=TRUE`, user `sa`, blank password.

---

## 2. Demo walkthrough

1. **Create account** → username, email, password.
2. **Link authenticator app**: scan the QR code and type the 6-digit code to confirm.
3. **Register face**: allow camera access; the page captures 5 samples.
4. You're sent to **Log in**:
   1. Enter password.
   2. Enter the current code from your app.
   3. Press **Start face scan**: look straight, turn your head to one side (a simple liveness check), then look back.
5. **Dashboard** shows your security status and a login history read from the `login_audit` SQL table.

To demo a failed face match, have a teammate try step 3 on your account.

---

## 3. Project structure

```
faceauth-mfa/
├── start.sh                                 one-command install + start
├── pom.xml                                  Maven build + dependencies
├── src/main/java/edu/groupproject/faceauth/
│   ├── FaceAuthApplication.java             main()
│   ├── config/
│   │   ├── AppConfig.java                   BCrypt, filters
│   │   ├── PageGuardFilter.java             protects dashboard.html / setup.html
│   │   ├── SecurityHeadersFilter.java       security headers, camera permission
│   │   └── SessionKeys.java
│   ├── controller/                          JSON REST API
│   │   ├── AuthController.java              register, login, verify-totp, verify-face, logout
│   │   ├── SetupController.java             QR code / TOTP confirm, face enrollment
│   │   ├── AccountController.java           /api/me (dashboard data)
│   │   ├── ApiErrorHandler.java             errors → {"error": "..."}
│   │   ├── ApiException.java, Sessions.java
│   ├── model/                               User, AuthStage, JSON request records (Dtos)
│   ├── repository/                          SQL (JdbcTemplate, prepared statements)
│   │   ├── UserRepository.java
│   │   ├── FaceRepository.java              stores descriptors as JSON text
│   │   └── AuditRepository.java
│   └── service/
│       ├── TotpService.java                 TOTP algorithm in plain Java
│       ├── FaceMatchService.java            Euclidean-distance face matching
│       └── QrCodeService.java               QR PNG for the authenticator app
├── src/main/resources/
│   ├── application.properties               settings (DB, thresholds, lockout)
│   ├── application-mysql.properties
│   ├── schema.sql                           database tables
│   └── static/                              website
│       ├── index.html, register.html, login.html, setup.html, dashboard.html
│       ├── css/styles.css
│       └── js/api.js, face.js, login.js, setup.js, dashboard.js
└── src/test/java/.../TotpServiceTest.java   checks TOTP against RFC 6238 test vectors
```

---

## 4. Database (SQL)

```
users             id, username, email, password_hash, totp_secret, totp_enabled,
                  last_totp_step, face_enrolled, failed_attempts, locked_until, created_at
face_descriptors  id, user_id → users.id, descriptor_json, created_at
login_audit       id, user_id → users.id, username, event, success, detail, ip_address, created_at
```

`descriptor_json` holds the 128 AI-model numbers as a JSON array, for example `[0.0123,-0.0871, …]`.

---

## 5. JSON API

| Method | Path | Body (JSON) | Needed stage |
|---|---|---|---|
| POST | `/api/auth/register` | `{"username","email","password"}` | none |
| POST | `/api/auth/login` | `{"username","password"}` | none |
| POST | `/api/auth/verify-totp` | `{"code":"123456"}` | PASSWORD_OK |
| POST | `/api/auth/verify-face` | `{"descriptor":[128 numbers]}` | TOTP_OK |
| POST | `/api/auth/logout` | none | any |
| GET | `/api/auth/status` | none | any |
| GET | `/api/setup/status` | none | SETUP / AUTHENTICATED |
| POST | `/api/setup/totp/start` | none → `{"secret","otpauthUri","qrDataUrl"}` | SETUP / AUTHENTICATED |
| POST | `/api/setup/totp/confirm` | `{"code":"123456"}` | SETUP / AUTHENTICATED |
| POST | `/api/setup/face/enroll` | `{"descriptors":[[128],[128],…]}` (3–10 samples) | SETUP / AUTHENTICATED |
| GET | `/api/me` | none | AUTHENTICATED |
| GET | `/api/me/logins` | none | AUTHENTICATED |

Errors always look like `{"error": "message"}`, returned with a matching HTTP status code (400, 401, 403, 409 or 423).

**Login state machine** (kept server-side in the HttpSession):
```
password ✔ → PASSWORD_OK → code ✔ → TOTP_OK → face ✔ → AUTHENTICATED
```
You can't skip a step: each endpoint checks the stage first.

---

## 6. How the face recognition works

The AI part is **face-api.js** (`@vladmandic/face-api`). It is plug-and-play: it loads from a CDN, the pre-trained models download automatically, and nothing needs to be trained.

1. **SSD MobileNet v1** finds the face in the webcam frame.
2. **Face Landmark 68** finds eyes, nose and mouth. These points also drive the head-turn liveness check.
3. **Face Recognition Net** (ResNet-34) turns the face into a **descriptor** of 128 numbers.

The browser sends **only those 128 numbers** to the server, never the photo. `FaceMatchService.java` computes the Euclidean distance to the enrolled samples. If the distance is **0.5 or less** (`app.face.match-threshold`), the face matches.

**Running offline:** download the model files from `https://cdn.jsdelivr.net/npm/@vladmandic/face-api@1.7.12/model/` (`ssd_mobilenetv1_model*`, `face_landmark_68_model*`, `face_recognition_model*`) into `src/main/resources/static/models/`, then change `FACE_MODEL_URL` in `js/face.js` to `'/models/'`.

---

## 7. Security features

- Passwords hashed with BCrypt (cost 12). Plain-text passwords are never stored.
- All SQL uses prepared statements, which prevents SQL injection.
- Account locks for 15 minutes after 5 wrong passwords.
- A login is cancelled after 5 wrong codes or face attempts.
- Each 6-digit code works only once (replay protection with `last_totp_step`).
- The session ID changes at every step (prevents session fixation). Cookies are HttpOnly and SameSite=Strict.
- A user who only knows the password can't replace an already-linked authenticator or enrolled face.
- Error messages don't reveal whether a username exists, and response timing is equalized too.
- Every step is written to an audit log.
- HTML output is escaped on the dashboard (prevents XSS).

### Known limitations (good to mention in the presentation)
- Face matching runs in the browser, so a modified client could send fake numbers. That's why the face is **one of three factors**, not the only one. A production system would run the model on the server or use certified liveness detection.
- The head-turn liveness check stops a printed photo but not a replayed video.
- Face recognition accuracy drops with poor lighting, and published research shows these models are less accurate for some demographic groups. Worth discussing in the ethics section.
- Store face data with consent. In many places it's legally sensitive biometric data (GDPR, Illinois BIPA, etc.).

---

## 8. Optional: HTTPS for testing from other devices

```bash
keytool -genkeypair -alias faceauth -keyalg RSA -keysize 2048 -storetype PKCS12 \
        -keystore keystore.p12 -validity 365 -storepass changeit -dname "CN=localhost"
```
Add to `application.properties`:
```
server.ssl.key-store=keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
```
Then open `https://<your-ip>:8080` and accept the self-signed certificate warning.

---

## 9. Suggested team split

| Member | Owns |
|---|---|
| A | Database: `schema.sql`, repositories, MySQL setup |
| B | Java auth logic: `AuthController`, `TotpService`, sessions/security |
| C | AI/face: `face.js`, `FaceMatchService`, threshold tuning, liveness |
| D | Front end: HTML pages, `styles.css`, page JS, UX |
| All | Testing, presentation, ethics/privacy write-up |
