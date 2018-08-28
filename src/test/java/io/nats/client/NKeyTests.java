// Copyright 2015-2018 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import org.junit.Test;

public class NKeyTests {
    private static final int ED25519_SIGNATURE_SIZE = 64;

    @Test
    public void testCRC16() throws Exception {
        // Example inputs and outputs from around the web
        byte[][] inputs = {
            {},
            "abc".getBytes(StandardCharsets.US_ASCII),
            "ABC".getBytes(StandardCharsets.US_ASCII),
            "This is a string".getBytes(StandardCharsets.US_ASCII),
            "123456789".getBytes(StandardCharsets.US_ASCII),
            "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.US_ASCII),
            {(byte)0x7F},
            {(byte)0x80},
            {(byte)0xFF},
            {0x0,0x1,0x7D,0x7E,(byte)0x7F, (byte) 0x80, (byte)0xFE, (byte)0xFF}
        };

        int[] expected = {
            0x0, // ""
            0x9DD6, // "abc"
            0x3994, // "ABC"
            0x21E3, // "This is a string"
            0x31C3, // "123456789"
            0xCBDE, // "abcdefghijklmnopqrstuvwxyz0123456789"
            0x8F78, // 0x7F
            0x9188, // 0x80
            0x1EF0, // 0xFF
            0xE26F, // {0x0,0x1,0x7D,0x7E, 0x7F, 0x80, 0xFE, 0xFF}
        };

        for (int i=0; i<inputs.length ; i++) {
            byte[] input = inputs[i];
            int crc = expected[i];
            int actual = NKey.crc16(input);
            assertEquals(String.format("CRC for \"%s\", should be 0x%08X but was 0x%08X", input, crc, actual), crc, actual);
        }
    }

    @Test
    public void testEncodeDecodeSeed() throws Exception {
        byte[] bytes = new byte[64];
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG", "SUN");
        random.nextBytes(bytes);

        String encoded = NKey.encodeSeed(NKey.Type.ACCOUNT, bytes);
        DecodedSeed decoded = NKey.decodeSeed(encoded);

        assertEquals(NKey.Type.fromPrefix(decoded.prefix), NKey.Type.ACCOUNT);
        assertTrue(Arrays.equals(bytes, decoded.bytes));
    }

    @Test
    public void testEncodeDecode() throws Exception {
        byte[] bytes = new byte[32];
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG", "SUN");
        random.nextBytes(bytes);

        String encoded = NKey.encode(NKey.Type.ACCOUNT, bytes);
        byte[] decoded = NKey.decode(NKey.Type.ACCOUNT, encoded);
        assertTrue(Arrays.equals(bytes, decoded));

        encoded = NKey.encode(NKey.Type.USER, bytes);
        decoded = NKey.decode(NKey.Type.USER, encoded);
        assertTrue(Arrays.equals(bytes, decoded));

        encoded = NKey.encode(NKey.Type.SERVER, bytes);
        decoded = NKey.decode(NKey.Type.SERVER, encoded);
        assertTrue(Arrays.equals(bytes, decoded));

        encoded = NKey.encode(NKey.Type.CLUSTER, bytes);
        decoded = NKey.decode(NKey.Type.CLUSTER, encoded);
        assertTrue(Arrays.equals(bytes, decoded));
    }

    @Test(expected=IllegalArgumentException.class)
    public void testDecodeWrongType() throws Exception {
        byte[] bytes = new byte[32];
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG", "SUN");
        random.nextBytes(bytes);

        String encoded = NKey.encode(NKey.Type.ACCOUNT, bytes);
        NKey.decode(NKey.Type.USER, encoded);
        assertFalse(true);
    }

    @Test(expected=IllegalArgumentException.class)
    public void testEncodeSeedSize() throws Exception {
        byte[] bytes = new byte[32];
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG", "SUN");
        random.nextBytes(bytes);

        NKey.encodeSeed(NKey.Type.ACCOUNT, bytes);
        assertFalse(true);
    }

    @Test(expected=IllegalArgumentException.class)
    public void testDecodeSize() throws Exception {
        NKey.decode(NKey.Type.ACCOUNT, "");
        assertFalse(true);
    }

    @Test
    public void testBadCRC() throws Exception {
        for (int i=0;i<1000;i++) {
            try {
                byte[] bytes = new byte[32];
                SecureRandom random = SecureRandom.getInstance("SHA1PRNG", "SUN");
                random.nextBytes(bytes);
        
                String encoded = NKey.encode(NKey.Type.ACCOUNT, bytes);
        
                StringBuilder builder = new StringBuilder();
        
                builder.append(encoded.substring(0, 6));
        
                if (encoded.charAt(6) == 'x' || encoded.charAt(6) == 'X') {
                    builder.append('Z');
                } else {
                    builder.append('X');
                }
                builder.append(encoded.substring(7));
        
                NKey.decode(NKey.Type.ACCOUNT, builder.toString());
                assertFalse(true);
            } catch (IllegalArgumentException e) {
                //expected
            }
        }
    }

    @Test
    public void testAccount() throws Exception {
        NKey theKey = NKey.createAccount(null);
        assertNotNull(theKey);

        String seed = theKey.getSeed();
        NKey.decodeSeed(seed); // throws if there is an issue

        assertEquals(NKey.fromSeed(theKey.getSeed()), NKey.fromSeed(theKey.getSeed()));

        String publicKey = theKey.getPublicKey();
        assertTrue(publicKey.charAt(0) == 'A');

        String privateKey = theKey.getPrivateKey();
        assertTrue(privateKey.charAt(0) == 'P');

        byte[] data = "Synadia".getBytes(StandardCharsets.UTF_8);
        byte[] sig = theKey.sign(data);

        assertEquals(sig.length, ED25519_SIGNATURE_SIZE);

        assertTrue(theKey.verify(data, sig));

        NKey otherKey = NKey.createAccount(null);
        assertFalse(otherKey.verify(data, sig));
        assertNotEquals(otherKey, theKey);
    }

    @Test
    public void testUser() throws Exception {
        NKey theKey = NKey.createUser(null);
        assertNotNull(theKey);

        String seed = theKey.getSeed();
        NKey.decodeSeed(seed); // throws if there is an issue

        assertEquals(NKey.fromSeed(theKey.getSeed()), NKey.fromSeed(theKey.getSeed()));

        String publicKey = theKey.getPublicKey();
        assertTrue(publicKey.charAt(0) == 'U');

        String privateKey = theKey.getPrivateKey();
        assertTrue(privateKey.charAt(0) == 'P');

        byte[] data = "Mister Zero".getBytes(StandardCharsets.UTF_8);
        byte[] sig = theKey.sign(data);

        assertEquals(sig.length, ED25519_SIGNATURE_SIZE);

        assertTrue(theKey.verify(data, sig));

        NKey otherKey = NKey.createUser(null);
        assertFalse(otherKey.verify(data, sig));
        assertNotEquals(otherKey, theKey);
    }

    @Test
    public void testCluster() throws Exception {
        NKey theKey = NKey.createCluster(null);
        assertNotNull(theKey);

        String seed = theKey.getSeed();
        NKey.decodeSeed(seed); // throws if there is an issue

        assertEquals(NKey.fromSeed(theKey.getSeed()), NKey.fromSeed(theKey.getSeed()));

        String publicKey = theKey.getPublicKey();
        assertTrue(publicKey.charAt(0) == 'C');

        String privateKey = theKey.getPrivateKey();
        assertTrue(privateKey.charAt(0) == 'P');

        byte[] data = "Connect Everything".getBytes(StandardCharsets.UTF_8);
        byte[] sig = theKey.sign(data);

        assertEquals(sig.length, ED25519_SIGNATURE_SIZE);

        assertTrue(theKey.verify(data, sig));

        NKey otherKey = NKey.createCluster(null);
        assertFalse(otherKey.verify(data, sig));
        assertNotEquals(otherKey, theKey);
    }

    @Test
    public void testServer() throws Exception {
        NKey theKey = NKey.createServer(null);
        assertNotNull(theKey);

        String seed = theKey.getSeed();
        NKey.decodeSeed(seed); // throws if there is an issue

        assertEquals(NKey.fromSeed(theKey.getSeed()), NKey.fromSeed(theKey.getSeed()));

        String publicKey = theKey.getPublicKey();
        assertTrue(publicKey.charAt(0) == 'N');

        String privateKey = theKey.getPrivateKey();
        assertTrue(privateKey.charAt(0) == 'P');

        byte[] data = "Polaris and Pluto".getBytes(StandardCharsets.UTF_8);
        byte[] sig = theKey.sign(data);

        assertEquals(sig.length, ED25519_SIGNATURE_SIZE);

        assertTrue(theKey.verify(data, sig));

        NKey otherKey = NKey.createServer(null);
        assertFalse(otherKey.verify(data, sig));
        assertNotEquals(otherKey, theKey);
    }

    @Test
    public void testPublicOnly() throws Exception {
        NKey theKey = NKey.createUser(null);
        assertNotNull(theKey);

        String publicKey = theKey.getPublicKey();

        assertEquals(NKey.fromPublicKey(publicKey), NKey.fromPublicKey(publicKey));
        assertEquals(NKey.fromPublicKey(publicKey).hashCode(), NKey.fromPublicKey(publicKey).hashCode());

        NKey pubOnly = NKey.fromPublicKey(publicKey);

        byte[] data = "Public and Private".getBytes(StandardCharsets.UTF_8);
        byte[] sig = theKey.sign(data);

        assertTrue(pubOnly.verify(data,sig));

        NKey otherKey = NKey.createServer(null);
        assertFalse(otherKey.verify(data, sig));
        assertNotEquals(otherKey, theKey);
        assertNotEquals(otherKey, pubOnly);
    }

    @Test(expected=IllegalStateException.class)
    public void testPublicOnlyCantSign() throws Exception {
        NKey theKey = NKey.createUser(null);
        NKey pubOnly = NKey.fromPublicKey(theKey.getPublicKey());

        byte[] data = "Public and Private".getBytes(StandardCharsets.UTF_8);
        pubOnly.sign(data);
    }

    @Test(expected=IllegalStateException.class)
    public void testPublicOnlyCantProvideSeed() throws Exception {
        NKey theKey = NKey.createUser(null);
        NKey pubOnly = NKey.fromPublicKey(theKey.getPublicKey());
        pubOnly.getSeed();
    }

    @Test(expected=IllegalStateException.class)
    public void testPublicOnlyCantProvidePrivate() throws Exception {
        NKey theKey = NKey.createUser(null);
        NKey pubOnly = NKey.fromPublicKey(theKey.getPublicKey());
        pubOnly.getPrivateKey();
    }

    @Test(expected=IllegalArgumentException.class)
    public void testPublicFromSeedShouldFail() throws Exception {
        NKey theKey = NKey.createUser(null);
        NKey.fromPublicKey(theKey.getSeed());
    }

    @Test(expected=IllegalArgumentException.class)
    public void testSeedFromPublicShouldFail() throws Exception {
        NKey theKey = NKey.createUser(null);
        NKey.fromSeed(theKey.getPublicKey());
    }

    @Test
    public void testFromSeed() throws Exception {
        NKey theKey = NKey.createAccount(null);
        assertNotNull(theKey);

        String seed = theKey.getSeed();
        assertEquals(NKey.fromSeed(seed), NKey.fromSeed(seed));
        assertEquals(NKey.fromSeed(seed).hashCode(), NKey.fromSeed(seed).hashCode());
        assertEquals(NKey.fromSeed(seed).getPublicKey(), NKey.fromSeed(seed).getPublicKey());
        assertEquals(NKey.fromSeed(seed).getPrivateKey(), NKey.fromSeed(seed).getPrivateKey());

        assertTrue(seed.startsWith("SA"));

        NKey fromSeed = NKey.fromSeed(seed);

        byte[] data = "Seeds into trees".getBytes(StandardCharsets.UTF_8);
        byte[] sig = theKey.sign(data);

        assertTrue(fromSeed.verify(data,sig));

        NKey otherKey = NKey.createServer(null);
        assertFalse(otherKey.verify(data, sig));
        assertNotEquals(otherKey, theKey);
        assertNotEquals(otherKey, fromSeed);
    }

    @Test(expected=IllegalArgumentException.class)
    public void testFromBadSeed() throws Exception {
        NKey.fromSeed("BadSeed");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testFromBadPublicKey() throws Exception {
        NKey.fromPublicKey("BadSeed");
    }

    @Test
    public void testBigSignVerify() throws Exception {
        NKey theKey = NKey.createAccount(null);
        assertNotNull(theKey);

        byte[] data = Files.readAllBytes(Paths.get("src/test/resources/keystore.jks"));
        byte[] sig = theKey.sign(data);

        assertEquals(sig.length, ED25519_SIGNATURE_SIZE);
        assertTrue(theKey.verify(data, sig));

        String publicKey = theKey.getPublicKey();
        assertTrue(NKey.fromPublicKey(publicKey).verify(data, sig));

        NKey otherKey = NKey.createUser(null);
        byte[] sig2 = otherKey.sign(data);

        assertFalse(otherKey.verify(data, sig));
        assertFalse(Arrays.equals(sig2, sig));
        assertTrue(otherKey.verify(data, sig2));
    }

    /*
        Compatibility/Interop data created from the following go code:
    	user, _ := nkeys.CreateUser(nil)
        seed, _ := user.Seed()
        publicKey, _ := user.PublicKey()
        privateKey, _ := user.PrivateKey()

        data := []byte("Hello World")
        sig, _ := user.Sign(data)
        encSig := base64.StdEncoding.EncodeToString(sig)

        fmt.Printf("Seed: %q\n", seed)
        fmt.Printf("Public: %q\n", publicKey)
        fmt.Printf("Private: %q\n", privateKey)

        fmt.Printf("Data: %q\n", data)
        fmt.Printf("Signature: %q\n", encSig)
     */
    @Test
    public void testInterop() throws Exception {
        String seed = "SUANUMOF6TPEPPH2GERC5RVRQKJDPJVX5D3VZCJYP2DNOGAGYC7J3I5ITEVHAL46IGWPHMKLQAPB6CILWESKAJG4PGOVBYI4BYQJS76YSJZLQ";
        String publicKey = "UCUJSKTQF6PEDLHTWFFYAHQ7BEF3CJFAETOHTHKQ4EOA4IEZP7MJFHTQ";
        String privateKey = "PDNDDRPU3ZD3Z6RREIXMNMMCSI32NN7I65OISOD6Q3LRQBWAX2O2HKEZFJYC7HSBVTZ3CS4ADYPQSC5RESQCJXDZTVIOCHAOECMX7WESPRRA";
        byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
        String encodedSig = "+KUbWqEzExkBlgI4wk1fph1DWHvE6bV2Ef2wYVZ1r2U15mWcll+PMbdyO/OyrB9nGxA0h9D6frkW/RYGKNIvAQ==";

        NKey fromSeed = NKey.fromSeed(seed);

        assertEquals(fromSeed.getType(), NKey.Type.USER);

        NKey fromPublicKey = NKey.fromPublicKey(publicKey);

        byte[] seedSig = fromSeed.sign(data);
        byte[] sig = Base64.getDecoder().decode(encodedSig);

        assertTrue(Arrays.equals(seedSig, sig));

        assertTrue(fromSeed.verify(data, sig));
        assertTrue(fromPublicKey.verify(data, sig));

        // Make sure generation is the same
        assertEquals(fromSeed.getSeed(), seed);
        assertEquals(fromSeed.getPublicKey(), publicKey);
        assertEquals(fromSeed.getPrivateKey(), privateKey);

        DecodedSeed decoded = NKey.decodeSeed(seed);
        String encodedSeed = NKey.encodeSeed(NKey.Type.fromPrefix(decoded.prefix), decoded.bytes);
        assertEquals(encodedSeed, seed);
    }
}