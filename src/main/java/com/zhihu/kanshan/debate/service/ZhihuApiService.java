package com.zhihu.kanshan.debate.service;

import com.zhihu.kanshan.debate.model.Answer;

import java.util.List;

public interface ZhihuApiService {
    List<Answer> searchAnswers(String topic, int limit);
    List<String> getHotTopics();
    String callZhidaAgent(String topic, String debateContext);
}
