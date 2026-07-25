package com.jack.autocodebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * Short-lived access metadata for an isolated generated-code preview.
 */
@Data
public class AppPreviewVO implements Serializable {

    private String previewUrl;

    /**
     * Preview expiration time in Unix epoch milliseconds.
     */
    private long expiresAt;

    private static final long serialVersionUID = 1L;
}
