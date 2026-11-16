package edu.groupproject.faceauth.model;

/**
 * Where a browser session is in the multi-factor flow. Stored in the HttpSession.
 *
 * <pre>
 *  Registration:  register ──► SETUP (scan QR + enroll face) ──► must log in
 *  Login:         password ──► PASSWORD_OK ──► TOTP_OK ──► AUTHENTICATED
 *                 (something you know) (something you have) (something you are)
 * </pre>
 */
public enum AuthStage {
    SETUP,
    PASSWORD_OK,
    TOTP_OK,
    AUTHENTICATED
}
