package com.minimall.infra.security;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.sys.api.dto.CaptchaView;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 图形验证码(架构文档 7.1.1)。
 *
 * <p>答案存 Redis 而不是签名进令牌里:签名方案把答案交给客户端保管,等于让攻击者拿到校验依据;
 * 存 Redis 才能做到**一次性**(用过即删)。
 *
 * <p>画图只用 JDK 的 {@code java.awt}/{@code javax.imageio},不引第三方验证码库 ——
 * 这个项目的依赖面一直收得很紧(MQ、加解密、Excel 都是能不引就不引)。
 */
@Component
public class CaptchaService {

    private static final String KEY_PREFIX = "captcha:";
    /** 去掉 0/O、1/I/L 这些看着像的字符:它们造成的失败全是"我明明输对了" */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final LoginProperties properties;

    public CaptchaService(StringRedisTemplate redis, LoginProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 生成一张新验证码并写入 Redis,返回可直接展示的 data URL。 */
    public CaptchaView generate() {
        String code = randomCode();
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(KEY_PREFIX + captchaId, code,
                Duration.ofSeconds(properties.captchaTtlSeconds()));
        return new CaptchaView(captchaId, "data:image/png;base64," + render(code));
    }

    /**
     * 校验验证码。**无论对错都立刻删除**:错一次就得重新取一张,
     * 否则同一个 captchaId 可以被反复试到对为止,一次性就白做了。
     */
    public void verify(String captchaId, String captchaCode) {
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new BusinessException(ErrorCode.CAPTCHA_REQUIRED);
        }
        String key = KEY_PREFIX + captchaId;
        String expected = redis.opsForValue().get(key);
        redis.delete(key);
        if (expected == null) {
            // 过期、伪造的 id、或已经用过一次,都归到"失效"
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
        if (!expected.equalsIgnoreCase(captchaCode.trim())) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }

    /** 白底 + 干扰线 + 逐字随机倾斜与颜色;不加噪点块,人一眼能读、脚本要费点劲。 */
    private String render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);

            for (int i = 0; i < 6; i++) {
                graphics.setColor(randomColor(150, 220));
                graphics.drawLine(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT),
                        RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT));
            }

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            int step = WIDTH / (CODE_LENGTH + 1);
            for (int i = 0; i < code.length(); i++) {
                graphics.setColor(randomColor(0, 120));
                double angle = (RANDOM.nextDouble() - 0.5) * 0.5;
                int x = step * (i + 1) - 8;
                int y = HEIGHT / 2 + 9 + RANDOM.nextInt(5) - 2;
                graphics.rotate(angle, x, y);
                graphics.drawString(String.valueOf(code.charAt(i)), x, y);
                graphics.rotate(-angle, x, y);
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException ex) {
            // 写内存流几乎不可能失败;真失败也不能把登录入口打成 500
            throw new UncheckedIOException(ex);
        }
    }

    private Color randomColor(int min, int max) {
        int range = max - min;
        return new Color(min + RANDOM.nextInt(range), min + RANDOM.nextInt(range), min + RANDOM.nextInt(range));
    }
}
