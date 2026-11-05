package com.endpointposture.worker;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.HexFormat;
/** Generates 256-bit worker secrets and stores only SHA-256 hashes. */
public final class WorkerCredentials {
    public static final String KEY_PREFIX="epw_", TOKEN_PREFIX="ept_";
    private static final int MAX_LENGTH=128;
    private static final SecureRandom RANDOM=new SecureRandom();
    private WorkerCredentials() {}
    public static String newKey(){return KEY_PREFIX+secret();}
    public static String newEnrollmentToken(){return TOKEN_PREFIX+secret();}
    public static boolean looksLikeKey(String v){return shape(v,KEY_PREFIX);}
    public static boolean looksLikeToken(String v){return shape(v,TOKEN_PREFIX);}
    public static String sha256Hex(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String secret(){byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private static boolean shape(String v,String p){return v!=null&&v.length()>p.length()&&v.length()<=MAX_LENGTH&&v.startsWith(p);}
}