package br.teste.deroyque.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

/**
 * Identifica o sistema consumidor a partir do token (Client Credentials). Usado para isolar os dados:
 * um consumidor só enxerga as solicitações que ele mesmo criou.
 */
public final class ClientIdentity {

    private ClientIdentity() {
    }

    public static String of(Jwt jwt) {
        for (String claim : new String[]{"client_id", "azp"}) {
            String value = jwt.getClaimAsString(claim);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return jwt.getSubject();
    }
}
