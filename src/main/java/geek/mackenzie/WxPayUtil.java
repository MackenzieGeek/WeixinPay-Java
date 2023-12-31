package geek.mackenzie;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSONObject;
import com.squareup.okhttp.HttpUrl;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;


/**
 * @author Joy
 * @date 2023-08-05
 */
public class WxPayUtil {

  // 请求网关
  private static final String url_prex = "https://api.mch.weixin.qq.com/";
  // 编码
  private static final String charset = "UTF-8";

  /**
   * 微信支付下单
   *
   * @param url 请求地址（只需传入域名之后的路由地址）
   * @param jsonStr 请求体 json字符串 此参数与微信官方文档一致
   * @param mchId 商户ID
   * @param serial_no 证书序列号
   * @param privateKeyFilePath 私钥的路径
   * @return 订单支付的参数
   * @throws Exception
   */
  public static String V3PayGet(
      String url, String jsonStr, String mchId, String serial_no, String privateKeyFilePath)
      throws Exception {
    String body = "";
    // 创建httpclient对象
    CloseableHttpClient client = HttpClients.createDefault();
    // 创建post方式请求对象
    HttpPost httpPost = new HttpPost(url_prex + url);
    // 装填参数
    StringEntity s = new StringEntity(jsonStr, charset);
    s.setContentEncoding(new BasicHeader(HTTP.CONTENT_TYPE, "application/json"));
    // 设置参数到请求对象中
    httpPost.setEntity(s);
    String post =
        getToken(HttpUrl.parse(url_prex + url), mchId, serial_no, privateKeyFilePath, jsonStr);
    // 设置header信息
    // 指定报文头【Content-type】、【User-Agent】
    httpPost.setHeader("Content-type", "application/json");
    httpPost.setHeader("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT; DigExt)");
    httpPost.setHeader("Accept", "application/json");
    httpPost.setHeader("Authorization", "WECHATPAY2-SHA256-RSA2048 " + post);
    // 执行请求操作，并拿到结果（同步阻塞）
    CloseableHttpResponse response = client.execute(httpPost);
    // 获取结果实体
    HttpEntity entity = response.getEntity();
    if (entity != null) {
      // 按指定编码转换结果实体为String类型
      body = EntityUtils.toString(entity, charset);
    }
    EntityUtils.consume(entity);
    // 释放链接
    response.close();
    switch (url) {
      case "v3/pay/transactions/app": // 返回APP支付所需的参数
        return JSONObject.parseObject(body).getString("prepay_id");
      case "v3/pay/transactions/jsapi": // 返回JSAPI支付所需的参数
        return JSONObject.parseObject(body).getString("prepay_id");
      case "v3/pay/transactions/native": // 返回native的请求地址
        return JSONObject.parseObject(body).getString("code_url");
      case "v3/pay/transactions/h5": // 返回h5支付的链接
        return JSONObject.parseObject(body).getString("h5_url");
    }
    return body;
  }

  /**
   * 微信调起支付参数 返回参数如有不理解 请访问微信官方文档 https://pay.weixin.qq.com/wiki/doc/apiv3/apis/chapter4_1_4.shtml
   *
   * @param prepayId 微信下单返回的prepay_id
   * @param appId 应用ID(appid)
   * @param privateKeyFilePath 私钥的地址
   * @return 当前调起支付所需的参数
   * @throws Exception
   */
  public static JSONObject WxTuneUp(String prepayId, String appId, String privateKeyFilePath)
      throws Exception {
    String time = System.currentTimeMillis() / 1000 + "";
    String nonceStr = UUID.randomUUID().toString().replace("-", "");
    ArrayList<String> list = new ArrayList<>();
    list.add(appId);
    list.add(time);
    list.add(nonceStr);
    list.add(prepayId);
    // 加载签名
    String packageSign = sign(buildSignMessage(list).getBytes(), privateKeyFilePath);
    JSONObject jsonObject = new JSONObject();
    jsonObject.put("appid", appId);
    jsonObject.put("timeStamp", time);
    jsonObject.put("nonceStr", nonceStr);
    jsonObject.put("prepayid", prepayId);
    jsonObject.put("package", "Sign=WXPay");
    jsonObject.put("sign", packageSign);
    return jsonObject;
  }

  /**
   * 构造签名串
   *
   * @param signMessage 待签名的参数
   * @return 构造后带待签名串
   */
  static String buildSignMessage(ArrayList<String> signMessage) {
    if (signMessage == null || signMessage.size() <= 0) {
      return null;
    }
    StringBuilder sbf = new StringBuilder();
    for (String str : signMessage) {
      sbf.append(str).append("\n");
    }
    return sbf.toString();
  }

  /**
   * 生成组装请求头
   *
   * @param url 请求地址
   * @param mercId 商户ID
   * @param serial_no 证书序列号
   * @param privateKeyFilePath 私钥路径
   * @param body 请求体
   * @return 组装请求的数据
   * @throws Exception
   */
  static String getToken(
      HttpUrl url, String mercId, String serial_no, String privateKeyFilePath, String body)
      throws Exception {
    String nonceStr = UUID.randomUUID().toString().replace("-", "");
    long timestamp = System.currentTimeMillis() / 1000;
    String message = buildMessage("POST", url, timestamp, nonceStr, body);
    String signature = sign(message.getBytes(StandardCharsets.UTF_8), privateKeyFilePath);
    return "mchid=\""
        + mercId
        + "\","
        + "nonce_str=\""
        + nonceStr
        + "\","
        + "timestamp=\""
        + timestamp
        + "\","
        + "serial_no=\""
        + serial_no
        + "\","
        + "signature=\""
        + signature
        + "\"";
  }

  /**
   * 处理微信异步回调
   *
   * @param request
   * @param response
   * @param privateKey 32的秘钥
   */
  public static String notify(
      HttpServletRequest request, HttpServletResponse response, String privateKey)
      throws Exception {
    Map<String, String> map = new HashMap<>(12);
    String result = readData(request);
    // 需要通过证书序列号查找对应的证书，verifyNotify 中有验证证书的序列号
    String plainText = verifyNotify(result, privateKey);
    if (StrUtil.isNotEmpty(plainText)) {
      response.setStatus(200);
      map.put("code", "SUCCESS");
      map.put("message", "SUCCESS");
    } else {
      response.setStatus(500);
      map.put("code", "ERROR");
      map.put("message", "签名错误");
    }
    response.setHeader("Content-type", ContentType.JSON.toString());
    response.getOutputStream().write(JSONUtil.toJsonStr(map).getBytes(StandardCharsets.UTF_8));
    response.flushBuffer();
    String out_trade_no = JSONObject.parseObject(plainText).getString("out_trade_no");
    return out_trade_no;
  }

  /**
   * v3 支付异步通知验证签名
   *
   * @param body 异步通知密文
   * @param key api 密钥
   * @return 异步通知明文
   * @throws Exception 异常信息
   */
  public static String verifyNotify(String body, String key) throws Exception {
    // 获取平台证书序列号
    cn.hutool.json.JSONObject resultObject = JSONUtil.parseObj(body);
    cn.hutool.json.JSONObject resource = resultObject.getJSONObject("resource");
    String cipherText = resource.getStr("ciphertext");
    String nonceStr = resource.getStr("nonce");
    String associatedData = resource.getStr("associated_data");
    AesUtil aesUtil = new AesUtil(key.getBytes(StandardCharsets.UTF_8));
    // 密文解密
    return aesUtil.decryptToString(
        associatedData.getBytes(StandardCharsets.UTF_8),
        nonceStr.getBytes(StandardCharsets.UTF_8),
        cipherText);
  }

  /**
   * 处理返回对象
   *
   * @param request
   * @return
   */
  public static String readData(HttpServletRequest request) {
    BufferedReader br = null;
    try {
      StringBuilder result = new StringBuilder();
      br = request.getReader();
      for (String line; (line = br.readLine()) != null; ) {
        if (result.length() > 0) {
          result.append("\n");
        }
        result.append(line);
      }
      return result.toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (br != null) {
        try {
          br.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  /**
   * 生成签名
   *
   * @param message 请求体
   * @param privateKeyFilePath 私钥的路径
   * @return 生成base64位签名信息
   * @throws Exception
   */
  static String sign(byte[] message, String privateKeyFilePath) throws Exception {
    Signature sign = Signature.getInstance("SHA256withRSA");
    sign.initSign(getPrivateKey(privateKeyFilePath));
    sign.update(message);
    return Base64.getEncoder().encodeToString(sign.sign());
  }

  /**
   * 组装签名加载
   *
   * @param method 请求方式
   * @param url 请求地址
   * @param timestamp 请求时间
   * @param nonceStr 请求随机字符串
   * @param body 请求体
   * @return 组装的字符串
   */
  static String buildMessage(
      String method, HttpUrl url, long timestamp, String nonceStr, String body) {
    String canonicalUrl = url.encodedPath();
    if (url.encodedQuery() != null) {
      canonicalUrl += "?" + url.encodedQuery();
    }
    return method + "\n" + canonicalUrl + "\n" + timestamp + "\n" + nonceStr + "\n" + body + "\n";
  }

  /**
   * 获取私钥。
   *
   * @param filename 私钥文件路径 (required)
   * @return 私钥对象
   */
  static PrivateKey getPrivateKey(String filename) throws IOException {
    String content = new String(Files.readAllBytes(Paths.get(filename)), StandardCharsets.UTF_8);
    try {
      String privateKey =
          content
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s+", "");
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("当前Java环境不支持RSA", e);
    } catch (InvalidKeySpecException e) {
      throw new RuntimeException("无效的密钥格式");
    }
  }
}
