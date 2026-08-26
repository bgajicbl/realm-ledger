package io.realmledger.adapter.web;

/** Uniform error body. {@code code} is stable and safe for clients to branch on. */
public record ApiError(String code, String message, Long available) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
