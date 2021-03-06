/*
 *
 *  The MIT License
 *
 *  Copyright 2019 ITON Solutions.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package org.iton.jssi.crypto;

import java.util.Base64;
import java.util.Map;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Base58;
import org.iton.jssi.crypto.algorithm.CryptoFactory;
import org.iton.jssi.crypto.algorithm.ICrypto;
import org.iton.jssi.crypto.algorithm.ICrypto.CryptoType;
import org.iton.jssi.crypto.util.Utils;
import org.iton.jssi.did.Did;
import org.iton.jssi.did.MyDidInfo;
import org.iton.jssi.did.TheirDid;
import org.iton.jssi.did.TheirDidInfo;
import org.iton.jssi.util.Pair;
import org.libsodium.api.Crypto_aead_chacha20poly1305_ietf;
import org.libsodium.api.Crypto_randombytes;
import org.libsodium.jni.SodiumConstants;
import static org.libsodium.jni.SodiumConstants.CRYPTO_AEAD_CHACHA20POLY1305_IETF_NONCEBYTES;
import org.libsodium.jni.SodiumException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author ITON Solutions
 */
public class CryptoService {
    
    private static final Logger LOG = LoggerFactory.getLogger(CryptoService.class);
    
    public Keys createKeys(KeyInfo info) throws SodiumException{
        LOG.debug(String.format("Create key: %s", info == null ? "no info" : info));
        
        ICrypto crypto;
        Keys keys;
        
        if(info == null){
            crypto = CryptoFactory.getCrypto();
            keys = crypto.createKeys(null);
        } else {
            crypto = CryptoFactory.getCrypto(info.cryptoType);
            byte[] seed = convertSeed(info.seed);
            keys = crypto.createKeys(seed);
        }
        keys.verkey = String.format("%s:%s", keys.verkey, crypto.getType().getName());
        return keys;
    }

    public Pair<Did, Keys> createMyDid(MyDidInfo info) throws SodiumException{
        LOG.debug(String.format("Create my did %s", info));

        ICrypto crypto = CryptoFactory.getCrypto(info.cryptoType);
        byte[] seed = convertSeed(info.seed);
        Keys keys = crypto.createKeys(seed);
        String did = null;

        if(info.did != null){
            did = validateDid(info.did) ? info.did : did;
        } else if(info.cid){
            did = keys.verkey;
        } else {
            byte[] abridged = new byte[0x10];
            byte[] bytes = Base58.decode(keys.verkey);
            System.arraycopy(bytes, 0, abridged, 0, abridged.length);
            did = Base58.encode(abridged);
        }

        if(!info.cryptoType.equals(CryptoType.DEFAULT_CRYPTO_TYPE)) {
            keys.verkey = String.format("%s:%s", keys.verkey, crypto.getType().getName());
        }
        return Pair.create(new Did(did, keys.verkey), keys);
    }
    
    public TheirDid createTheirDid(TheirDidInfo info) throws CryptoException{
        LOG.debug(String.format("Create their did %s", info));
        
        String verkey = Utils.buildFullVerkey(info.did, info.verkey);
        validateKey(verkey);
        TheirDid result = new TheirDid(info.did, verkey);
        return result;
    }
    
    public ComboBox comboBox(Keys sender, Keys receiver, byte[] data) throws CryptoException, SodiumException{
        LOG.debug(String.format("Combobox encrypt: my pk: &s their pk: %s", sender.verkey, receiver.verkey));
        
        CryptoBox box = cryptoBox(data, sender, receiver);
        ComboBox result = new ComboBox(
                Base64.getEncoder().encodeToString(box.cipher),
                sender.verkey,
                Base64.getEncoder().encodeToString(box.nonce));
        
        return result;
    }
    
    
    /*
     * Public-key authenticated encryption
     *
     * # Security model
     * The cryptoBox function is designed to meet the standard notions of privacy and
     * third-party unforgeability for a public-key authenticated-encryption scheme
     * using nonces. For formal definitions see, e.g., Jee Hea An, "Authenticated
     * encryption in the public-key setting: security notions and analyses,"
     * <http://eprint.iacr.org/2001/079>.
     *
     * Distinct messages between the same {sender, receiver} set are required
     * to have distinct nonces. For example, the lexicographically smaller
     * public key can use nonce 1 for its first message to the other key, nonce
     * 3 for its second message, nonce 5 for its third message, etc., while the
     * lexicographically larger public key uses nonce 2 for its first message
     * to the other key, nonce 4 for its second message, nonce 6 for its third
     * message, etc. Nonces are long enough that randomly generated nonces have
     * negligible risk of collision.
     *
     * There is no harm in having the same nonce for different messages if the
     * {sender, receiver} sets are different. This is true even if the sets
     * overlap. For example, a sender can use the same nonce for two different
     * messages if the messages are sent to two different public keys.
     *
     * The cryptoBox function is not meant to provide non-repudiation. On the
     * contrary: the cryptoBox function guarantees repudiability. A receiver
     * can freely modify a boxed message, and therefore cannot convince third
     * parties that this particular message came from the sender. The sender
     * and receiver are nevertheless protected against forgeries by other
     * parties. In the terminology of
     * <http://groups.google.com/group/sci.crypt/msg/ec5c18b23b11d82c>,
     * crypto_box uses "public-key authenticators" rather than "public-key
     * signatures."
     *
     * Users who want public verifiability (or receiver-assisted public
     * verifiability) should instead use signatures (or signcryption).
     * Signature support is a high priority for `NaCl`; a signature API will be
     * described in subsequent `NaCl` documentation.
     */
    public CryptoBox cryptoBox(byte[] data, Keys sender, Keys receiver) throws CryptoException, SodiumException{
        LOG.debug(String.format("Cryptobox encrypt: my pk: %s their pk: %s", sender.verkey, receiver.verkey));
        
        String verkey;
        String type1, type2;
        
        if(receiver.verkey.contains(":")){
            String[] splits = receiver.verkey.split(":");
            type2 = splits[1];
        } else {
            type2 = CryptoType.DEFAULT_CRYPTO_TYPE.getName();
        }
        
        if(CryptoType.toType(type2) == null){
            throw new CryptoException(String.format("Trying to use key with unknown crypto: %s", type2));
        }
        
        if(sender.verkey.contains(":")){
            String[] splits = sender.verkey.split(":");
            verkey = splits[0];
            type1 = splits[1];
        } else {
            verkey = sender.verkey;
            type1 = CryptoType.DEFAULT_CRYPTO_TYPE.getName();
        }
        
        if(!type1.equals(type2)){
            LOG.error(String.format("My key crypto type is incompatible with their key crypto type: %s %s", type1, type2));
            return null;
        }
        
        ICrypto crypto = CryptoFactory.getCrypto(type2);
        byte[] sk = Base58.decode(receiver.signkey);
        byte[] pk = Base58.decode(verkey);
        byte[] nonce = crypto.genNonce();
        byte[] cipher = crypto.cryptoBox(data, nonce, pk, sk);
        CryptoBox result = new CryptoBox(cipher, nonce);
        return result;
    }
    
    public byte[] cryptoBoxOpen(byte[] cipher, byte[] nonce, Keys sender, Keys receiver) throws CryptoException, SodiumException{
        LOG.debug(String.format("Cryptobox decrypt: my pk: %s their pk: %s", sender.verkey, receiver.verkey));
        
        String  verkey;
        String type1, type2;
        
        if(receiver.verkey.contains(":")){
            String[] splits = receiver.verkey.split(":");
            type2 = splits[1];
        } else {
            type2 = CryptoType.DEFAULT_CRYPTO_TYPE.getName();
        }
        
        if(CryptoType.toType(type2) == null){
            throw new CryptoException(String.format("Trying to use key with unknown crypto: %s", type2));
        }
        
        if(sender.verkey.contains(":")){
            String[] splits = sender.verkey.split(":");
            verkey = splits[0];
            type1 = splits[1];
        } else {
            verkey = sender.verkey;
            type1 = CryptoType.DEFAULT_CRYPTO_TYPE.getName();
        }
        
        if(!type2.equals(type1)){
            throw new CryptoException(String.format("My key crypto type is incompatible with their key crypto type: %s must be %s", type1, type2));
        }
        
        ICrypto crypto = CryptoFactory.getCrypto(type2);
        byte[] sk = Base58.decode(receiver.signkey);
        byte[] pk = Base58.decode(verkey);
        byte[] data = crypto.cryptoBoxOpen(cipher, nonce, pk, sk);
        return data;
    }
    
    public byte[] cryptoBoxSeal(Keys keys, byte[] data) throws CryptoException, SodiumException{
        LOG.debug(String.format("Cryptobox seal encrypt pk: %s (sk: %s)", keys.verkey, keys.signkey));

        String verkey;
        String type;
        
        if(keys.verkey.contains(":")){
            String[] splits = keys.verkey.split(":");
            verkey = splits[0];
            type = splits[1];
        } else {
            verkey = keys.verkey;
            type = CryptoType.DEFAULT_CRYPTO_TYPE.getName();
        }
        
        if(CryptoType.toType(type) == null){
            throw new CryptoException(String.format("Trying to use key with unknown crypto: %s", type));
        }
        
        ICrypto crypto = CryptoFactory.getCrypto(type);
        byte[] pk = Base58.decode(verkey);
        byte[] result = crypto.cryptoBoxSeal(data, pk);
        return result;
    }
    
    public byte[] cryptoBoxSealOpen(Keys keys, byte[] cipher) throws CryptoException, SodiumException{
        LOG.debug(String.format("Cryptobox seal decrypt pk: %s (sk: %s)", keys.verkey, keys.signkey));

        String verkey;
        String type;
        
        if(keys.verkey.contains(":")){
            String[] splits = keys.verkey.split(":");
            verkey = splits[0];
            type = splits[1];
        } else {
            verkey = keys.verkey;
            type = CryptoType.DEFAULT_CRYPTO_TYPE.getName();
        }
        
        if(CryptoType.toType(type) == null){
            throw new CryptoException(String.format("Trying to use key with unknown crypto: %s", type));
        }
        
        ICrypto crypto = CryptoFactory.getCrypto(type);
        byte[] pk = Base58.decode(verkey);
        byte[] sk = Base58.decode(keys.signkey);
        byte[] result = crypto.cryptoBoxSealOpen(cipher, pk, sk);
        return result;
    }
    
    public byte[] sign(byte[] data, Keys keys) throws SodiumException, CryptoException {
        LOG.debug(String.format("pk: %s (sk: %s)", keys.verkey, keys.signkey));

        String type;
        
        if(keys.verkey.contains(":")){
            String[] splits = keys.verkey.split(":");
            type = splits[1];
        } else {
            type = CryptoType.DEFAULT_CRYPTO_TYPE.getName();
        }
        
        if(CryptoType.toType(type) == null){
            throw new CryptoException(String.format("Trying to use key with unknown crypto: %s", type));
        }
        
        ICrypto crypto = CryptoFactory.getCrypto(type);
        byte[] result = crypto.sign(data, Base58.decode(keys.signkey));

        return result;
    }
    
    public boolean verify(byte[] data, byte[] sign, Keys keys) throws SodiumException, CryptoException {
        LOG.debug(String.format("pk: %s (sk: %s)", keys.verkey, keys.signkey));

        String verkey;
        String type;
        
        if(keys.verkey.contains(":")){
            String[] splits = keys.verkey.split(":");
            verkey = splits[0];
            type = splits[1];
        } else {
            verkey = keys.verkey;
            type = CryptoType.DEFAULT_CRYPTO_TYPE.getName();
        }
        
        if(CryptoType.toType(type) == null){
            throw new CryptoException(String.format("Trying to use key with unknown crypto: %s", type));
        }
        
        ICrypto crypto = CryptoFactory.getCrypto(type);
        boolean result = crypto.verify(data, sign, Base58.decode(verkey));
        return result;
    }
    
    public byte[] convertSeed(String seed) throws IllegalArgumentException{
        LOG.debug(String.format("Convert seed: %s", seed == null ? "no seed" : seed));

        if (seed == null) {
            return null;
        }

        byte[] bytes;

        if (seed.endsWith("=")) {
            bytes = Base64.getDecoder().decode(seed);
        } else if (seed.length() == SodiumConstants.CRYPTO_SIGN_ED25519_SEEDBYTES * 2) {
            bytes = Utils.toBytes(seed);
        } else {
            bytes = seed.getBytes();
        }

        return bytes;
    }
    
    public boolean validateDid(String did){
        LOG.debug(String.format("Validate did: %s", did == null ? "no did" : did));
        
        if(did == null){
            return false;
        }
        
        byte[] bytes = Base58.decode(did);
        if(bytes.length == 0x10 || bytes.length == 0x20){
            return true;
        }
        
        LOG.error(String.format("Trying to use DID with unexpected length: %d. The 16- or 32-byte number upon which a DID is based should be 22/23 or 44/45 bytes when encoded as base58", bytes.length));
        return false;
    }
    
    public CryptoDetached encryptPlaintext(byte[] data, byte[] add, Keys keys) throws SodiumException, AddressFormatException{
        
        if(add == null){
            add = new byte[0];
        }
        
        byte[] signkey = Base58.decode(keys.signkey);
        byte[] nonce = new byte[CRYPTO_AEAD_CHACHA20POLY1305_IETF_NONCEBYTES];
        Crypto_randombytes.increment(nonce);
        
        Map<String, byte[]> result = Crypto_aead_chacha20poly1305_ietf.encrypt_detached(data, add, nonce, signkey);
        
        byte[] cipher = result.get("cipher");
        byte[] tag = result.get("tag");
        
        CryptoDetached box = new CryptoDetached(
                Base64.getEncoder().encodeToString(cipher),
                Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(tag));
        
        return box;
    }
    
    public String decryptPlaintext(CryptoDetached box, byte[] add, Keys keys) throws SodiumException, IllegalArgumentException, AddressFormatException{
        
        if (add == null) {
            add = new byte[0];
        }
        
        byte[] signkey = Base58.decode(keys.signkey);
        byte[] cipher = Base64.getDecoder().decode(box.cipher);
        byte[] nonce = Base64.getDecoder().decode(box.nonce);
        byte[] tag = Base64.getDecoder().decode(box.tag);

        byte[] data = Crypto_aead_chacha20poly1305_ietf.decrypt_detached(cipher, tag, add, nonce, signkey);
        return new String(data);
    }
    
    public void validateKey(String verkey) throws CryptoException, AddressFormatException{
        LOG.debug(String.format("Validate did %s", verkey));
        
        String key;
        String type;
        
        if(verkey.contains(":")){
            String[] splits = verkey.split(":");
            key = splits[0];
            type = splits[1];
        } else {
            key = verkey;
            type = CryptoType.DEFAULT_CRYPTO_TYPE.getName();
        }
        
        if(CryptoType.toType(type) == null){
            throw new CryptoException(String.format("Trying to use key with unknown crypto: %s", type));
        }
        
        ICrypto crypto = CryptoFactory.getCrypto(type);
        if(key.startsWith("~")){
            Base58.decode(key.substring(1));
        } else {
            crypto.validateKeys(key);
        }
    }
}
