package com.zhihu.kanshan.debate.service;

import com.zhihu.kanshan.debate.model.Answer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Mock implementation — replace with real Zhihu API client once credentials are available.
 * Each topic has ~12 answers covering 3-4 stances with realistic content and author metadata.
 */
@Service
public class ZhihuApiMockService implements ZhihuApiService {

    private static final Map<String, List<Answer>> MOCK_DATA = Map.of(
        "30岁该不该转行", buildCareerChangeAnswers(),
        "中医是不是科学", buildTcmAnswers(),
        "考研还是直接工作", buildGradVsWorkAnswers()
    );

    @Override
    public List<Answer> searchAnswers(String topic, int limit) {
        String key = MOCK_DATA.keySet().stream()
            .filter(k -> topic.contains(k) || k.contains(topic))
            .findFirst()
            .orElse(null);

        List<Answer> answers = key != null
            ? MOCK_DATA.get(key)
            : buildGenericAnswers(topic);

        return answers.stream().limit(limit).toList();
    }

    @Override
    public List<String> getHotTopics() {
        return List.of(
            "30岁该不该转行",
            "中医是不是科学",
            "考研还是直接工作",
            "房价还会继续涨吗",
            "躺平还是卷"
        );
    }

    @Override
    public String callZhidaAgent(String topic, String debateContext) {
        // Placeholder — real impl calls Zhihu Zhida Agent API (100次/天 budget)
        return "[直答Agent综述] 本场辩论围绕「" + topic + "」展开，各方观点均有其合理性。"
            + "建议读者结合自身实际情况，参考多方论据后做出判断。";
    }

    // ── Mock answer datasets ──────────────────────────────────────────────────

    private static List<Answer> buildCareerChangeAnswers() {
        return List.of(
            Answer.builder()
                .id("c001").topic("30岁该不该转行").authorName("程序员老陈").authorUrl("https://www.zhihu.com/people/laoChen")
                .answerUrl("https://www.zhihu.com/question/123456/answer/001").upvotes(12400)
                .content("30岁转行我亲身经历，从金融转IT，用了18个月，现在薪资翻了1.5倍。关键不是年龄，是你有没有想清楚目标赛道。" +
                    "30岁的优势是执行力和自律，你不会像22岁那样三天打鱼两天晒网。")
                .keySnippets(List.of(
                    "30岁转行从金融到IT，用了18个月，薪资翻了1.5倍",
                    "30岁的优势是执行力和自律，不会像22岁那样三天打鱼两天晒网",
                    "关键不是年龄，是有没有想清楚目标赛道"))
                .build(),
            Answer.builder()
                .id("c002").topic("30岁该不该转行").authorName("HR张薇").authorUrl("https://www.zhihu.com/people/zhangWei_hr")
                .answerUrl("https://www.zhihu.com/question/123456/answer/002").upvotes(9800)
                .content("作为HR我见过太多30岁转行失败的案例。大部分人低估了重新起步的心理成本——从资深变成新人，" +
                    "薪资倒退3-5年，还要跟25岁的应届生竞争。除非原行业真的没有出路，否则建议在现有赛道深耕。")
                .keySnippets(List.of(
                    "大部分人低估了重新起步的心理成本——从资深变成新人",
                    "薪资倒退3-5年，还要跟25岁的应届生竞争",
                    "除非原行业真的没有出路，否则建议在现有赛道深耕"))
                .build(),
            Answer.builder()
                .id("c003").topic("30岁该不该转行").authorName("职业规划师李明").authorUrl("https://www.zhihu.com/people/liMing_career")
                .answerUrl("https://www.zhihu.com/question/123456/answer/003").upvotes(8700)
                .content("这个问题问错了。应该问的是：转行的边际成本vs边际收益。30岁转行成本最高的是时间机会成本，" +
                    "但如果你在一个收缩行业，不转才是最大的风险。我服务过的客户里，30岁转行成功率约40%，失败原因基本是准备不足。")
                .keySnippets(List.of(
                    "30岁转行成本最高的是时间机会成本",
                    "如果你在一个收缩行业，不转才是最大的风险",
                    "30岁转行成功率约40%，失败原因基本是准备不足"))
                .build(),
            Answer.builder()
                .id("c004").topic("30岁该不该转行").authorName("媒体人王芳").authorUrl("https://www.zhihu.com/people/wangFang_media")
                .answerUrl("https://www.zhihu.com/question/123456/answer/004").upvotes(7600)
                .content("我31岁从传统媒体转互联网内容，现在35岁回头看，那是我做过最正确的决定。传统媒体在走下坡路，" +
                    "30岁那年不转，35岁会后悔。趋势比经验更重要，选对方向，努力才有意义。")
                .keySnippets(List.of(
                    "31岁从传统媒体转互联网内容，是最正确的决定",
                    "趋势比经验更重要，选对方向，努力才有意义",
                    "30岁不转，35岁会后悔"))
                .build(),
            Answer.builder()
                .id("c005").topic("30岁该不该转行").authorName("互联网老兵赵刚").authorUrl("https://www.zhihu.com/people/zhaoGang_internet")
                .answerUrl("https://www.zhihu.com/question/123456/answer/005").upvotes(6900)
                .content("以招聘者角度说：30岁跨行转行的简历，我们基本不看。不是歧视，是效率问题。" +
                    "同等职位有5年相关经验的候选人，我为什么要选一个从零开始的？除非你能证明你的跨行经验是真正的加分项。")
                .keySnippets(List.of(
                    "30岁跨行转行的简历，作为招聘者基本不看",
                    "有5年相关经验的候选人，为什么要选一个从零开始的",
                    "除非能证明跨行经验是真正的加分项"))
                .build(),
            Answer.builder()
                .id("c006").topic("30岁该不该转行").authorName("心理咨询师陈静").authorUrl("https://www.zhihu.com/people/chenJing_psy")
                .answerUrl("https://www.zhihu.com/question/123456/answer/006").upvotes(5400)
                .content("很多人转行的真实动机是逃避，而不是追求。先搞清楚你是「对这个行业没热情了」还是「对自己的当前状态不满意」。" +
                    "如果是后者，换行业解决不了问题，6个月后你会同样迷茫。先解决内心的问题，再谈转行。")
                .keySnippets(List.of(
                    "很多人转行的真实动机是逃避，而不是追求",
                    "搞清楚是「对行业没热情」还是「对当前状态不满」",
                    "如果是逃避，换行业解决不了问题，6个月后同样迷茫"))
                .build(),
            Answer.builder()
                .id("c007").topic("30岁该不该转行").authorName("独立顾问刘宇").authorUrl("https://www.zhihu.com/people/liuYu_consultant")
                .answerUrl("https://www.zhihu.com/question/123456/answer/007").upvotes(4800)
                .content("30岁转行最好的方式是「斜杠过渡」而不是「直接跳船」。在现有工作的同时，用6-12个月验证新方向，" +
                    "有了稳定收入来源再全职转。我认识的成功案例，80%都是这么做的。直接辞职转行的，成功率低很多。")
                .keySnippets(List.of(
                    "最好是「斜杠过渡」而不是「直接跳船」",
                    "用6-12个月验证新方向，有了稳定收入再全职转",
                    "成功案例80%都是斜杠过渡，直接辞职成功率低"))
                .build(),
            Answer.builder()
                .id("c008").topic("30岁该不该转行").authorName("金融从业者小北").authorUrl("https://www.zhihu.com/people/xiaoBei_finance")
                .answerUrl("https://www.zhihu.com/question/123456/answer/008").upvotes(4200)
                .content("金融行业视角：我们公司每年都会引进一批30岁左右有其他行业背景的人，特别欢迎有科技、法律、医疗背景的。" +
                    "跨行的人往往带来新的思维框架，在专业能力到位的前提下，跨行经验反而是稀缺资产。")
                .keySnippets(List.of(
                    "金融公司每年引进30岁左右有其他行业背景的人",
                    "跨行的人往往带来新的思维框架",
                    "专业能力到位的前提下，跨行经验反而是稀缺资产"))
                .build()
        );
    }

    private static List<Answer> buildTcmAnswers() {
        return List.of(
            Answer.builder()
                .id("t001").topic("中医是不是科学").authorName("医学博士周航").authorUrl("https://www.zhihu.com/people/zhouHang_md")
                .answerUrl("https://www.zhihu.com/question/234567/answer/001").upvotes(18700)
                .content("从现代科学哲学的角度，中医不满足波普尔的可证伪性标准。很多中医理论无法通过双盲对照实验验证，" +
                    "这是科学界对中医最核心的质疑。但这不等于中医无效，只是说它不符合现代意义上的「科学」定义。")
                .keySnippets(List.of(
                    "中医不满足波普尔的可证伪性标准",
                    "很多中医理论无法通过双盲对照实验验证",
                    "中医不符合科学定义，但这不等于无效"))
                .build(),
            Answer.builder()
                .id("t002").topic("中医是不是科学").authorName("中医师王汉章").authorUrl("https://www.zhihu.com/people/wangHanzhang_tcm")
                .answerUrl("https://www.zhihu.com/question/234567/answer/002").upvotes(14300)
                .content("中医有几千年的实践验证，这本身就是一种循证。青蒿素来自中医理论指引，挽救了数百万人生命。" +
                    "用西方的科学框架来评判中医，本身就是方法论错误。中医是经验科学、整体科学，不是实验科学。")
                .keySnippets(List.of(
                    "中医有几千年的实践验证，本身就是一种循证",
                    "青蒿素来自中医理论指引，挽救了数百万人生命",
                    "中医是经验科学、整体科学，不是实验科学"))
                .build(),
            Answer.builder()
                .id("t003").topic("中医是不是科学").authorName("科学哲学研究者林琦").authorUrl("https://www.zhihu.com/people/linQi_philo")
                .answerUrl("https://www.zhihu.com/question/234567/answer/003").upvotes(11200)
                .content("「科学」这个词本身在不同语境下含义不同。中医在中国传统认识论框架内是严格的知识体系，" +
                    "但在现代西方科学框架内确实存在合法性危机。与其争「是不是科学」，不如问「对患者有没有效」。")
                .keySnippets(List.of(
                    "「科学」在不同语境下含义不同",
                    "中医在传统认识论框架内是严格的知识体系",
                    "与其争是否科学，不如问对患者是否有效"))
                .build(),
            Answer.builder()
                .id("t004").topic("中医是不是科学").authorName("循证医学专家陈胜利").authorUrl("https://www.zhihu.com/people/chenShengli_ebm")
                .answerUrl("https://www.zhihu.com/question/234567/answer/004").upvotes(9600)
                .content("我做过中医疗效的系统综述，结果令人沮丧：大多数中医疗法在严格的随机对照试验中效果不优于安慰剂。" +
                    "少数有效的，如针灸治疗某些疼痛，效果也是微弱的。我们应该要求中医按照科学标准证明自己的疗效。")
                .keySnippets(List.of(
                    "大多数中医疗法在随机对照试验中效果不优于安慰剂",
                    "少数有效的如针灸，效果也是微弱的",
                    "应该要求中医按照科学标准证明疗效"))
                .build()
        );
    }

    private static List<Answer> buildGradVsWorkAnswers() {
        return List.of(
            Answer.builder()
                .id("g001").topic("考研还是直接工作").authorName("985硕士应届生小雪").authorUrl("https://www.zhihu.com/people/xiaoXue_grad")
                .answerUrl("https://www.zhihu.com/question/345678/answer/001").upvotes(9400)
                .content("考研三年，工资从5k起步到12k。同期工作的同学已经是15-18k了。" +
                    "如果你的目标行业看重学历（高校、科研、大厂产品岗），考研值得。否则这三年的时间成本真的很高。")
                .keySnippets(List.of(
                    "考研三年后工资12k，同期工作的同学已经15-18k",
                    "目标行业看重学历（高校、科研、大厂）考研值得",
                    "否则三年的时间成本很高"))
                .build(),
            Answer.builder()
                .id("g002").topic("考研还是直接工作").authorName("互联网产品经理张浩").authorUrl("https://www.zhihu.com/people/zhangHao_pm")
                .answerUrl("https://www.zhihu.com/question/345678/answer/002").upvotes(7800)
                .content("我直接工作6年，没有考研，现在年薪50万+。互联网行业根本不看学历，看作品集和项目经验。" +
                    "三年考研的时间，我已经做了4个完整的产品从0到1，这个经验是买不来的。")
                .keySnippets(List.of(
                    "直接工作6年，现在年薪50万+，没有考研",
                    "互联网行业根本不看学历，看作品集和项目经验",
                    "三年考研时间里已经做了4个产品从0到1"))
                .build(),
            Answer.builder()
                .id("g003").topic("考研还是直接工作").authorName("高校教师刘建国").authorUrl("https://www.zhihu.com/people/liuJianguo_prof")
                .answerUrl("https://www.zhihu.com/question/345678/answer/003").upvotes(6500)
                .content("在高校工作，我明确告诉你：本科生找我们这类工作的门都没有，最低博士，顶级高校要海归博士。" +
                    "如果目标是体制内、高校、国企研究院，考研是必选项，不是加分项，是门槛。")
                .keySnippets(List.of(
                    "高校工作最低要求博士，顶级高校要海归博士",
                    "目标是体制内、高校、国企研究院，考研是门槛",
                    "对这类目标，考研不是加分项而是必选项"))
                .build()
        );
    }

    private static List<Answer> buildGenericAnswers(String topic) {
        return List.of(
            Answer.builder()
                .id("gen001").topic(topic).authorName("知乎用户A").authorUrl("https://www.zhihu.com/people/userA")
                .answerUrl("https://www.zhihu.com/question/gen/answer/001").upvotes(3200)
                .content("关于「" + topic + "」，我认为需要从多个维度来看。首先要考虑个人情况，其次是客观条件，最后才是社会环境。")
                .keySnippets(List.of("需要从多个维度来看", "首先考虑个人情况，其次客观条件"))
                .build(),
            Answer.builder()
                .id("gen002").topic(topic).authorName("知乎用户B").authorUrl("https://www.zhihu.com/people/userB")
                .answerUrl("https://www.zhihu.com/question/gen/answer/002").upvotes(2800)
                .content("反对上一个回答。「" + topic + "」这个问题没有标准答案，但有些原则是通用的。不要让焦虑替你做决定。")
                .keySnippets(List.of("没有标准答案，但有通用原则", "不要让焦虑替你做决定"))
                .build(),
            Answer.builder()
                .id("gen003").topic(topic).authorName("知乎用户C").authorUrl("https://www.zhihu.com/people/userC")
                .answerUrl("https://www.zhihu.com/question/gen/answer/003").upvotes(1900)
                .content("补充一个实际操作者的视角：我亲身经历过「" + topic + "」这个问题，结论是没有放之四海皆准的答案，具体情况具体分析。")
                .keySnippets(List.of("亲身经历，没有放之四海皆准的答案", "具体情况具体分析"))
                .build()
        );
    }
}
