package com.zhihu.kanshan.debate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Answer {
    private String id;
    private String topic;
    private String authorName;
    private String authorUrl;
    private String answerUrl;
    private String content;
    private int upvotes;
    private List<String> keySnippets; // 2-3 key argument excerpts for RAG
}
