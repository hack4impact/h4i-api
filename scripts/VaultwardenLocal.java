import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Sets up a throwaway local Vaultwarden (1.37) for the portal's Vaultwarden adapter (wiki: steps/stage-2/package-3-vaultwarden):
 * registers a PBKDF2 service admin and two members (example.test, local-only passwords), creates an organization as the
 * admin, and prints the env lines the portal needs, API key and master password included. Run by
 * scripts/vaultwarden-local.sh; uses the same Bitwarden client-side crypto as adapters/vaultwarden/VaultwardenCrypto.kt.
 */
public class VaultwardenLocal {
  static final String BASE = System.getenv().getOrDefault("VW", "http://127.0.0.1:8090");
  static final HttpClient http = HttpClient.newHttpClient();
  static final SecureRandom rnd = new SecureRandom();
  static final Base64.Encoder b64 = Base64.getEncoder();

  static byte[] hmac(byte[] k, byte[] d) throws Exception { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(k, "HmacSHA256")); return m.doFinal(d); }
  static byte[] cat(byte[] a, byte[] b) { byte[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }
  static byte[] masterKey(String pw, String email, int it) throws Exception {
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), email.toLowerCase().getBytes(), it, 256)).getEncoded();
  }
  /** PBKDF2-SHA256, one iteration, password = master key bytes, salt = password. */
  static String passwordHash(byte[] mk, String pw) throws Exception { return b64.encodeToString(hmac(mk, cat(pw.getBytes(StandardCharsets.UTF_8), new byte[]{0,0,0,1}))); }
  static byte[] stretch(byte[] mk) throws Exception { return cat(hmac(mk, cat("enc".getBytes(), new byte[]{1})), hmac(mk, cat("mac".getBytes(), new byte[]{1}))); }
  static String enc(byte[] plain, byte[] key) throws Exception {
    byte[] iv = new byte[16]; rnd.nextBytes(iv);
    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(key, 0, 32), "AES"), new IvParameterSpec(iv));
    byte[] data = c.doFinal(plain);
    return "2." + b64.encodeToString(iv) + "|" + b64.encodeToString(data) + "|" + b64.encodeToString(hmac(Arrays.copyOfRange(key, 32, 64), cat(iv, data)));
  }
  static String rsa(byte[] plain, PublicKey pub) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
    c.init(Cipher.ENCRYPT_MODE, pub, new OAEPParameterSpec("SHA-1", "MGF1", java.security.spec.MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
    return "4." + b64.encodeToString(c.doFinal(plain));
  }
  static byte[] random(int n) { byte[] b = new byte[n]; rnd.nextBytes(b); return b; }
  static KeyPair rsaPair() throws Exception { KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); }

  static String send(String method, String path, String body, String type, String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (type != null) b.header("Content-Type", type);
    if (token != null) b.header("Authorization", "Bearer " + token);
    b.header("Bitwarden-Client-Name", "web").header("Bitwarden-Client-Version", "2025.1.0").header("Device-Type", "9");
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() >= 300) throw new RuntimeException(method + " " + path + " -> " + r.statusCode() + " " + r.body());
    return r.body();
  }
  static String json(String path, String body, String token) throws Exception { return send("POST", path, body, "application/json", token); }
  static String field(String json, String name) { Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json); if (!m.find()) throw new RuntimeException(name + " not in " + json); return m.group(1); }
  static String form(Map<String, String> f) { StringJoiner j = new StringJoiner("&"); f.forEach((k, v) -> j.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8))); return j.toString(); }

  static String register(String email, String pw, int it) throws Exception {
    byte[] mk = masterKey(pw, email, it);
    byte[] userKey = random(64);
    KeyPair kp = rsaPair();
    // Vaultwarden 1.37: two steps; without SMTP the verification token comes back in the first response (a JSON string).
    String verify = json("/identity/accounts/register/send-verification-email", "{\"email\":\"" + email + "\",\"name\":\"" + email + "\",\"receiveMarketingEmails\":false}", null).trim().replace("\"", "");
    json("/identity/accounts/register/finish", "{\"email\":\"" + email + "\",\"emailVerificationToken\":\"" + verify + "\",\"masterPasswordHash\":\"" + passwordHash(mk, pw) + "\",\"userSymmetricKey\":\"" + enc(userKey, stretch(mk))
      + "\",\"kdf\":0,\"kdfIterations\":" + it + ",\"userAsymmetricKeys\":{\"publicKey\":\"" + b64.encodeToString(kp.getPublic().getEncoded()) + "\",\"encryptedPrivateKey\":\"" + enc(kp.getPrivate().getEncoded(), userKey) + "\"}}", null);
    return b64.encodeToString(kp.getPublic().getEncoded());
  }
  static String login(String email, String pw, int it) throws Exception {
    String body = send("POST", "/identity/connect/token", form(new LinkedHashMap<>(Map.of("grant_type", "password", "username", email, "password", passwordHash(masterKey(pw, email, it), pw),
      "scope", "api offline_access", "client_id", "web", "deviceType", "9", "deviceIdentifier", UUID.randomUUID().toString(), "deviceName", "firefox"))), "application/x-www-form-urlencoded", null);
    return field(body, "access_token");
  }

  public static void main(String[] a) throws Exception {
    String email = "service-admin@example.test", pw = "local-only correct horse battery staple"; int it = 600000;
    String pub = register(email, pw, it);
    register("member@example.test", "member password, local only", it);
    register("member2@example.test", "member password, local only", it);
    String token = login(email, pw, it);
    byte[] orgKey = random(64);
    KeyPair orgKp = rsaPair();
    PublicKey userPub = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(pub)));
    String org = json("/api/organizations", "{\"name\":\"Portal Local\",\"billingEmail\":\"" + email + "\",\"collectionName\":\"" + enc("Default collection".getBytes(), orgKey) + "\",\"key\":\"" + rsa(orgKey, userPub)
      + "\",\"keys\":{\"publicKey\":\"" + b64.encodeToString(orgKp.getPublic().getEncoded()) + "\",\"encryptedPrivateKey\":\"" + enc(orgKp.getPrivate().getEncoded(), orgKey) + "\"},\"planType\":0}", token);
    String orgId = field(org, "id");
    String userId = field(send("GET", "/api/accounts/profile", null, null, token), "id");
    String apiKey = field(json("/api/accounts/api-key", "{\"masterPasswordHash\":\"" + passwordHash(masterKey(pw, email, it), pw) + "\"}", token), "apiKey");
    System.out.println("PORTAL_ADAPTERS_VAULTWARDEN_ENABLED=true");
    System.out.println("PORTAL_ADAPTERS_VAULTWARDEN_BASEURL=" + BASE);
    System.out.println("PORTAL_ADAPTERS_VAULTWARDEN_ORGANIZATIONID=" + orgId);
    System.out.println("PORTAL_ADAPTERS_VAULTWARDEN_CLIENTID=user." + userId);
    System.out.println("PORTAL_ADAPTERS_VAULTWARDEN_CLIENTSECRET=" + apiKey);
    System.out.println("PORTAL_ADAPTERS_VAULTWARDEN_MASTERPASSWORD=\"" + pw + "\"");
  }
}
