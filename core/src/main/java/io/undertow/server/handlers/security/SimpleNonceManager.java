/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.undertow.server.handlers.security;

import static io.undertow.UndertowMessages.MESSAGES;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * A default {@link NonceManager} implementation to provide reasonable single host management of nonces.
 *
 * This {@link NonceManager} manages nonces in two groups, the first is the group that are allocated to new requests, this group
 * is a problem as we want to be able to limit how many we distribute so we don't have a DOS storing too many but we also don't
 * a high number of requests to to push the other valid nonces out faster than they can be used.
 *
 * The second group is the set of nonces actively in use - these should be maintained as we can also maintain the nonce count
 * and even track the next nonce once invalid.
 *
 * Maybe group one should be a timestamp and private key hashed together, if used with a nonce count they move to be tracked to
 * ensure the same count is not used again - if successfully used without a nonce count add to a blacklist until expiration? A
 * nonce used without a nonce count will essentially be single use with each request getting a new nonce.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class SimpleNonceManager implements SessionNonceManager {

    private static final String DEFAULT_HASH_ALG = "MD5";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * List of invalid nonces, this list contains the nonces that have been used without a nonce count.
     *
     * In that situation they are considered single use and must not be used again.
     */
    private final List<NonceKey> invalidNonces = new LinkedList<NonceKey>();

    /**
     * Map of known currently valid nonces, a SortedMap is used to order the nonces by their creation time stamp allowing a
     * simple iteration over the keys to identify expired nonces.
     */
    private final SortedMap<NonceKey, NonceValue> knownNonces = new TreeMap<NonceKey, NonceValue>();

    /**
     * A WeakHashMap to map expired nonces to their replacement nonce. For an item to be added to this Collection the key will
     * have been removed from the knownNonces map.
     *
     * A replacement nonce will have been added to knownNonces that references the key used here - once the replacement nonce is
     * removed from knownNonces then the key will be eligible for garbage collection allowing it to be removed from this map as
     * well.
     *
     * The value in this Map is a plain String, this is to avoid inadvertantly creating a long term reference to the key we
     * expect to be garbage collected at some point in the future.
     */
    private final Map<NonceKey, String> forwardMapping = new WeakHashMap<SimpleNonceManager.NonceKey, String>();

    /**
     * A pseudo-random generator for creating the nonces, a secure random is not required here as this is used purely to
     * minimise the chance of colisions should two nonces be generated at exactly the same time.
     */
    private final Random random = new Random();

    private final String secret;
    private final String hashAlg;
    private final int hashLength;

    /**
     * After a nonce is issued the first authentication response MUST be received within 5 minutes.
     */
    private final long firstUseTimeOut = 5 * 60 * 1000;

    /**
     * Overall a nonce is valid from 15 minutes from first being issued, if used after this then a new nonce will be issued.
     */
    private final long overallTimeOut = 15 * 60 * 1000;

    /**
     * This is the time before the expiration of the current nonce that a replacement nonce will be sent to the client
     * pro-actively, i.e. from 5 minutes before the expiration of the nonce the client will be asked to use the next nonce.
     */
    private final long newNonceOverlap = 5 * 60 * 1000;

    public SimpleNonceManager() {
        this(DEFAULT_HASH_ALG);
    }

    public SimpleNonceManager(final String hashAlg) {
        // Verify it is a valid algorithm (at least for now)
        MessageDigest digest = getDigest(hashAlg);

        this.hashAlg = hashAlg;
        this.hashLength = digest.getDigestLength();

        // Create a new secret only valid within this NonceManager instance.
        Random rand = new SecureRandom();
        byte[] secretBytes = new byte[32];
        rand.nextBytes(secretBytes);
        secret = Base64.encodeBytes(digest.digest(secretBytes));
    }

    private MessageDigest getDigest(final String hashAlg) {
        try {
            return MessageDigest.getInstance(hashAlg);
        } catch (NoSuchAlgorithmException e) {
            throw MESSAGES.hashAlgorithmNotFound(hashAlg);
        }
    }

    /**
     *
     * @see io.undertow.server.handlers.security.NonceManager#nextNonce(java.lang.String)
     */
    @Override
    public String nextNonce(String lastNonce) {
        if (lastNonce == null) {
            return createNewNonce();
        }

        // TODO - Add timestamp checking and forwarding to a new nonce.
        return lastNonce;
    }

    private String createNewNonce() {
        byte[] prefix = new byte[8];
        random.nextBytes(prefix);
        byte[] now = Long.toString(System.currentTimeMillis()).getBytes(UTF_8);

        return createNonce(prefix, now);
    }

    /**
     *
     * @see io.undertow.server.handlers.security.NonceManager#validateNonce(java.lang.String, int)
     */
    @Override
    public boolean validateNonce(String nonce, int nonceCount) {
        NonceKey key = new NonceKey(nonce);
        if (nonceCount < 0) {
            if (invalidNonces.contains(key)) {
                // Without a nonce count the nonce is only useable once.
                return false;
            }
            // Not already known so will drop into first use validation.
        } else if (knownNonces.containsKey(key)) {
            // At this point we need to validate that the nonce is still within it's time limits,
            // If a new nonce had been selected then a known nonce would not have been found.
            // The nonce will also have it's key sequence checked - we are not mandating the order
            // of the count but we are mandating a count can only be used once.

        } else if (forwardMapping.containsKey(key)) {
            // We could have let this drop through as the next validation would fail anyway but
            // why waste the time if we already know a replacement nonce has been issued.
            return false;
        }

        // This is not a nonce currently known to us so start the validation process.
        NonceKey nonceKey = verifyUnknownNonce(nonce);
        if (nonceKey == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        long earliestAccepted = now - firstUseTimeOut;
        if (nonceKey.timeStamp < earliestAccepted || nonceKey.timeStamp > now) {
            // The embedded timestamp is either expired or somehow is after now.
            return false;
        }

        if (nonceCount < 0) {
            // Allow a single use but reject all further uses.
            addInvalidNonce(nonceKey);
            return true;
        }

        // TODO - Implement nonceCount support.
        return false;

        // Should this also be tied to a user? i.e. a different user can not use someone elses nonce or is the count enough to
        // pick up abuse?
    }

    private void addInvalidNonce(final NonceKey nonce) {
        synchronized (invalidNonces) {
            // TODO - We really need a recurring task to clean these up but for now clean on each addition.
            long earliestAccepted = System.currentTimeMillis() - firstUseTimeOut;
            Iterator<NonceKey> it = invalidNonces.iterator();
            while (it.hasNext()) {
                NonceKey current = it.next();
                if (current.timeStamp < earliestAccepted) {
                    it.remove();
                } else {
                    break;
                }
            }

            invalidNonces.add(invalidNonces.size(), nonce);
        }
    }

    /**
     * Verify a previously unknown nonce and return the {@link NonceKey} representation for the nonce.
     *
     * Later when a nonce is re-used we can match based on the String alone - the information embedded within the nonce will be
     * cached with it.
     *
     * This stage of the verification simply extracts the prefix and the embedded timestamp and recreates a new hashed and
     * Base64 nonce based on the local secret - if the newly generated nonce matches the supplied one we accept it was created
     * by this nonce manager.
     *
     * This verification does not validate that the timestamp is within a valid time period.
     *
     * @param nonce -
     * @return
     */
    private NonceKey verifyUnknownNonce(final String nonce) {
        byte[] complete;
        try {
            complete = Base64.decode(nonce.getBytes(UTF_8));
        } catch (IOException e) {
            throw MESSAGES.invalidBase64Token(e);
        }

        int timeStampLength = complete[8];
        // A sanity check to try and verify the sizes we expect from the arrays are correct.
        if (hashLength > 0) {
            int expectedLength = 9 + timeStampLength + hashLength;
            if (complete.length != expectedLength) {
                throw MESSAGES.invalidNonceReceived();
            } else if (timeStampLength + 1 >= complete.length)
                throw MESSAGES.invalidNonceReceived();
        }

        byte[] prefix = new byte[8];
        System.arraycopy(complete, 0, prefix, 0, 8);
        byte[] timeStampBytes = new byte[timeStampLength];
        System.arraycopy(complete, 9, timeStampBytes, 0, timeStampBytes.length);

        String expectedNonce = createNonce(prefix, timeStampBytes);

        if (expectedNonce.equals(nonce)) {
            try {
                long timeStamp = Long.parseLong(new String(timeStampBytes, UTF_8));

                return new NonceKey(expectedNonce, timeStamp);
            } catch (NumberFormatException dropped) {
            }
        }

        return null;
    }

    private String createNonce(final byte[] prefix, final byte[] timeStamp) {
        byte[] hashedPart = generateHash(prefix, timeStamp);
        byte[] complete = new byte[9 + timeStamp.length + hashedPart.length];
        System.arraycopy(prefix, 0, complete, 0, 8);
        complete[8] = (byte) timeStamp.length;
        System.arraycopy(timeStamp, 0, complete, 9, timeStamp.length);
        System.arraycopy(hashedPart, 0, complete, 9 + timeStamp.length, hashedPart.length);

        return Base64.encodeBytes(complete);
    }

    private byte[] generateHash(final byte[] prefix, final byte[] timeStamp) {
        MessageDigest digest = getDigest(hashAlg);

        digest.update(prefix);
        digest.update(timeStamp);

        return digest.digest(secret.getBytes(UTF_8));
    }

    public void associateHash(String nonce, byte[] hash) {
        // TODO Auto-generated method stub

    }

    public byte[] lookupHash(String nonce) {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Key used to reference known nonces.
     *
     * This key serves two purposes, firstly it is used for looking up information about a known nonce from the SortedMap, for
     * this purpose hashCode and equals only take the nonce into account.
     *
     * The second purpose is to allow ordering based on the time the nonce was created, in this case comparison is only based on
     * the created timestamp.
     *
     * Note: this class has a natural ordering that is inconsistent with equals.
     */
    private class NonceKey implements Comparable<NonceKey> {

        private final String nonce;
        private final long timeStamp;

        NonceKey(final String nonce) {
            this(nonce, -1);
        }

        NonceKey(final String nonce, final long timeStamp) {
            this.nonce = nonce;
            this.timeStamp = timeStamp;
        }

        public int compareTo(NonceKey other) {
            if (timeStamp == other.timeStamp) {
                return 0;
            } else if (timeStamp < other.timeStamp) {
                return -1;
            }

            return 1;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + getOuterType().hashCode();
            result = prime * result + ((nonce == null) ? 0 : nonce.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            NonceKey other = (NonceKey) obj;
            if (!getOuterType().equals(other.getOuterType()))
                return false;
            if (nonce == null) {
                if (other.nonce != null)
                    return false;
            } else if (!nonce.equals(other.nonce))
                return false;
            return true;
        }

        private SimpleNonceManager getOuterType() {
            return SimpleNonceManager.this;
        }

    }

    /**
     * The state associated with a nonce.
     *
     * A NonceKey for a preciously valid nonce is also referenced, this is so that a WeakHashMap can be used to maintain a
     * mapping from the original NonceKey to the new nonce value.
     */
    private class NonceValue {

        private NonceKey previousKey;
        private byte[] sessionKey;

        byte[] getSessionKey() {
            return sessionKey;
        }

        void setSessionKey(final byte[] sessionKey) {
            this.sessionKey = sessionKey;
        }

    }

    public static void main(String[] args) throws Exception {
        NonceManager nm = new SimpleNonceManager();
        String nonce = nm.nextNonce(null);
        System.out.println("Nonce = " + nonce);
        System.out.println("Is Valid = " + nm.validateNonce(nonce, -1));
        System.out.println("Is Valid = " + nm.validateNonce(nonce.substring(0, nonce.length() - 2) + "A=", -1));
    }

}
