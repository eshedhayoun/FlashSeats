package com.flashseats.shared.identity;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller declare a {@link SessionId} parameter and receive the verified {@code fsid}.
 *
 * <p>This is the <em>only</em> way identity enters a handler. There is no path by which a request
 * body, query parameter or custom header can supply one (ADR-010), and no module has to reference
 * {@code bot} to find out who is calling.
 */
@Component
public class SessionIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return SessionId.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest request,
            WebDataBinderFactory binderFactory) {

        Object value = request.getAttribute(SessionId.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (value instanceof String id && !id.isBlank()) {
            return new SessionId(id);
        }
        // The identity filter issues a cookie on first contact, so this means the filter did not
        // run — a misconfiguration, not a client error the user can act on.
        throw new SessionInvalidException();
    }

    /** No verified {@code fsid} on the request. */
    static final class SessionInvalidException extends FlashSeatsException {
        SessionInvalidException() {
            super(ErrorCode.SESSION_INVALID, "No valid session. Reload the page to obtain one.");
        }
    }
}
