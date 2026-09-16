package com.fitmind.auth.gateway.util;

import cn.hutool.crypto.KeyUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.asymmetric.AsymmetricAlgorithm;
import cn.hutool.http.HttpUtil;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.fitmind.auth.common.constants.JwtConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.List;
import java.util.concurrent.*;

/**
 * JWT 公钥持有者。
 *
 * <p>JWT 采用 RS256 非对称加密：<b>fm-auth 用私钥签名，网关用公钥验签</b>。
 * 网关启动后会异步地：</p>
 * <ol>
 *     <li>从 Nacos 找到 fm-auth 的一个实例；</li>
 *     <li>HTTP 请求它的 {@code GET /jwks} 拿到 Base64 编码的公钥；</li>
 *     <li>构建 {@link JWTSigner} 缓存起来，供后续验签使用；</li>
 *     <li>失败则 10 秒后重试（解决服务启动顺序问题）。</li>
 * </ol>
 *
 * <p>之所以用非对称而非对称密钥：公钥可以安全地分发到网关，私钥只留在认证服务，
 * 即使网关被攻破也无法伪造 token。</p>
 */
@Slf4j
public class JwtSignerHolder {

    /** 认证服务在 Nacos 中的服务名 */
    private static final String AUTH_SERVICE_NAME = "fm-auth";

    /** 重试间隔（毫秒） */
    private static final long RETRY_INTERVAL_MS = 10_000L;

    private volatile JWTSigner jwtSigner;

    private final DiscoveryClient discoveryClient;

    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            r -> new Thread(r, "FitMindFetchJwkThread"));

    public JwtSignerHolder(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    /** 获取验签器，可能为 null（公钥尚未加载成功） */
    public JWTSigner getJwtSigner() {
        return jwtSigner;
    }

    public void init() {
        executor.submit(this::loadPublicKey);
    }

    public void shutdown() {
        executor.shutdown();
        log.debug("销毁加载公钥线程 FitMindFetchJwkThread");
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loadPublicKey() {
        while (jwtSigner == null) {
            try {
                // 1.从 Nacos 找认证服务实例
                log.info("尝试从 Nacos 加载认证服务地址...");
                List<ServiceInstance> instances = discoveryClient.getInstances(AUTH_SERVICE_NAME);
                if (instances == null || instances.isEmpty()) {
                    log.error("认证服务地址加载失败：实例列表为空，{}ms 后重试", RETRY_INTERVAL_MS);
                    sleep(RETRY_INTERVAL_MS);
                    continue;
                }
                ServiceInstance instance = instances.get(0);
                String jwkUri = String.format("http://%s:%d/jwks", instance.getHost(), instance.getPort());
                log.info("认证服务地址加载成功：{}", jwkUri);

                // 2.拉取公钥
                String base64PublicKey = HttpUtil.get(jwkUri, StandardCharsets.UTF_8);
                if (base64PublicKey == null || base64PublicKey.isBlank()) {
                    log.error("公钥加载失败：返回内容为空，{}ms 后重试", RETRY_INTERVAL_MS);
                    sleep(RETRY_INTERVAL_MS);
                    continue;
                }

                // 3.构建验签器
                PublicKey publicKey = KeyUtil.generatePublicKey(
                        AsymmetricAlgorithm.RSA_ECB_PKCS1.getValue(),
                        SecureUtil.decode(base64PublicKey));
                this.jwtSigner = JWTSignerUtil.createSigner(JwtConstants.JWT_ALGORITHM, publicKey);
                log.info("公钥加载成功，JWT 验签器已就绪");
            } catch (Exception e) {
                log.error("公钥加载失败：{}，{}ms 后重试", e.getMessage(), RETRY_INTERVAL_MS);
                sleep(RETRY_INTERVAL_MS);
            }
        }
        // 加载成功，释放线程池
        shutdown();
    }
}
