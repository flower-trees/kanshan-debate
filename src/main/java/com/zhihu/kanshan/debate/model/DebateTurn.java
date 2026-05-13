package com.zhihu.kanshan.debate.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DebateTurn {

    public enum Type { HOST, OPENING, CROSS, SUMMARY }

    private String id;
    private Type type;
    private String debaterName;   // stance label, e.g. "支持转行派"
    private String stanceId;
    private String emoji;
    private String text;
    private List<Citation> citations;
    private long timestamp;       // epoch millis
}
