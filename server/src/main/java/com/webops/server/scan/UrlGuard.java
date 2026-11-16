package com.webops.server.scan;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.webops.server.config.WebOpsProperties;

/**
 * 사용자가 입력한 주소를 검사해도 되는지 확인한다.
 * - http/https만 허용
 * - 기본적으로 localhost·사설 IP·클라우드 메타데이터 같은 내부 주소는 차단 (SSRF 방지)
 */
@Component
public class UrlGuard {

    private final boolean allowPrivateTargets;

    public UrlGuard(WebOpsProperties properties) {
        this.allowPrivateTargets = properties.scan().allowPrivateTargets();
    }

    /** @return 정리된 주소 (프로토콜이 없으면 https를 붙인다) */
    public String check(String input) {
        if (input == null || input.isBlank()) {
            throw new InvalidTargetException("검사할 주소를 입력해 주세요.");
        }
        String text = input.strip();
        if (!text.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            text = "https://" + text;
        }

        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new InvalidTargetException("주소 형식이 올바르지 않습니다.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidTargetException("http 또는 https 주소만 검사할 수 있습니다.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidTargetException("주소에서 도메인을 찾을 수 없습니다.");
        }

        if (!allowPrivateTargets) {
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new InvalidTargetException("존재하지 않는 도메인입니다: " + host);
            }
            for (InetAddress address : addresses) {
                if (isInternal(address)) {
                    throw new InvalidTargetException("내부망·로컬 주소는 검사할 수 없습니다: " + host);
                }
            }
        }
        return uri.toString();
    }

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true; // 0.0.0.0, 127.x, 169.254.x(메타데이터 포함), 10.x, 172.16~31.x, 192.168.x 등
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127) // 통신사 공유 대역
                    || (first == 198 && (second == 18 || second == 19)); // 벤치마크 대역
        }
        return (b[0] & 0xfe) == 0xfc; // IPv6 사설 대역 fc00::/7
    }
}
