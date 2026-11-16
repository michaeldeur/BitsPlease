package edu.groupproject.faceauth.config;

/** Names of the attributes we keep in the server-side HttpSession. */
public final class SessionKeys {
    private SessionKeys() {}

    public static final String USER_ID = "userId";
    public static final String STAGE = "authStage";
    /** Secret generated during setup, only saved to the DB once the user proves they scanned it. */
    public static final String PENDING_TOTP_SECRET = "pendingTotpSecret";
    /** Wrong TOTP / face attempts within one login; too many kicks the user back to the password step. */
    public static final String STEP_FAILURES = "stepFailures";
}
