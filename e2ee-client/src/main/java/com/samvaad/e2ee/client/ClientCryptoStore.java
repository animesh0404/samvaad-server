package com.samvaad.e2ee.client;

/**
 * Unified durability owner for all client-side E2EE crypto state.
 *
 * <p>This interface extends the three single-concern stores ({@link
 * SessionStore}, {@link DeviceKeyStore}, {@link TrustStore}) so that the
 * operations which must be crash-atomic <em>across</em> concerns have one
 * explicit boundary. Single-row reads/writes keep the semantics of the
 * extended interfaces; the two multi-row transitions below are only ever
 * performed through the atomic methods declared here (and, for the outbound
 * path, on {@link SessionStore}).
 *
 * <h2>Mandatory atomic boundaries</h2>
 * <ol>
 *   <li><b>Outbound encrypt commit</b> —
 *       {@link SessionStore#commitOutboundCiphertext}: the advanced session
 *       blob and the {@code COMMITTED} slot carrying its ciphertext become
 *       durable together, or neither does. Recovery can never observe a
 *       durable session-advanced/ciphertext-missing state.</li>
 *   <li><b>Inbound establishment commit</b> —
 *       {@link #commitInboundEstablishment}: the inbound READY session and
 *       the consumption of the referenced one-time prekey become durable
 *       together, or neither does.</li>
 * </ol>
 *
 * <h2>Inbound OTPK durability rule</h2>
 * <p>A crash between the inbound session write and the OTPK consumption must
 * not silently produce either half-state:
 * <ul>
 *   <li><em>OTPK consumed, session missing</em> is forbidden: consumption is
 *       recorded only inside {@link #commitInboundEstablishment}, after the
 *       session row is staged in the same transaction/snapshot.</li>
 *   <li><em>Session durable, OTPK still resolvable</em> after a crash is
 *       resolved by the retry rule, not by a second consumption: if the
 *       atomic commit never completed, neither half is visible, so a retry
 *       re-resolves the same OTPK and commits once. If the commit completed,
 *       replay of the same envelope fails closed at resolution time (see
 *       {@link DeviceKeyStore#requireOneTimePrivate}) and never consumes
 *       another OTPK, while the converged session stays usable for
 *       subsequent messages.</li>
 * </ul>
 *
 * <h2>Private-key protection</h2>
 * <p>Implementations persist sealed private material exclusively as opaque
 * handle references (the stable {@link
 * SignalAdapter.SealedPrivateHandle#handleId} UUIDs), never as key bytes.
 * The platform keystore (Android Keystore, WebCrypto non-extractable keys,
 * OS keychain, or the JVM reference file below) owns the material; this
 * store owns only the reference and the lifecycle flags. Raw private key
 * bytes must never appear in any persisted record.
 *
 * <h2>Production backends</h2>
 * <p>The server's PostgreSQL/Liquibase schema is deliberately <em>not</em>
 * reused here: it belongs to the server transport trust domain, which must
 * never hold client private handles or session state (ADR-0018). Each
 * client platform provides its own {@code ClientCryptoStore}: the reference
 * JVM implementation persists an atomic file snapshot; Android may use
 * Room/SQLite in one database transaction plus the Android Keystore;
 * Web/Tauri may use IndexedDB plus WebCrypto. All backends must honor the
 * same two atomic boundaries and the same recovery rules.
 *
 * <p>Ownership is single-process: implementations are not required to
 * arbitrate concurrent writers from two processes; the platform owns
 * process-lifetime discipline.
 */
public interface ClientCryptoStore extends SessionStore, DeviceKeyStore, TrustStore {

    /**
     * Durable atomic commit of one inbound establishment: the inbound READY
     * session AND the consumption of the referenced one-time prekey become
     * visible together, or neither does.
     *
     * @param inboundSession a READY session record with a non-null blob;
     *                       becomes the durable session for the peer
     * @param consumedOneTimePrekeyIdOrNull the adapter-reported consumed OTPK
     *                       ID, or null for signed-prekey-fallback envelopes
     *                       that referenced no OTPK
     */
    void commitInboundEstablishment(
            CryptoTypes.SessionRecord inboundSession, Integer consumedOneTimePrekeyIdOrNull);
}
