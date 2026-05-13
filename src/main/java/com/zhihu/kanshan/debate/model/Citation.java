package com.zhihu.kanshan.debate.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Citation {
    private String snippet;
    private String authorName;
    private String authorUrl;
    private String answerUrl;
    private int upvotes;
}
