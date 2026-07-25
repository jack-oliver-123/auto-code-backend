package com.jack.autocodebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Metadata returned after an application deployment completes.
 */
@Data
public class AppDeployVO implements Serializable {

    private String deployKey;

    private String deployUrl;

    private Date deployedTime;

    private static final long serialVersionUID = 1L;
}
