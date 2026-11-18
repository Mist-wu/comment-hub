package io.commenthub.datagen;

import java.util.random.RandomGenerator;

/**
 * 模板生成中文评论。没有直接使用公开数据集（版权与下载稳定性），README 里写明数据是生成的。
 * 约 3% 混入辱骂 / 诈骗词、3% 混入引流广告，让审核有东西可判。
 * 输出保证不含制表符、换行和反斜杠，可以直接写进 TSV 给 LOAD DATA。
 */
public final class Texts {
    private static final String[] OPEN = {"", "", "", "说真的，", "哈哈哈哈", "前排，", "来了来了，", "不得不说，", "第一次刷到，", "看完了，",
            "又是我，", "路过，", "怎么说呢，", "我宣布，", "真心觉得，", "求求了，", "老粉报到，", "破防了，", "笑死，", "家人们，"};
    private static final String[] SUBJ = {"这个视频", "up主", "这剪辑", "这个配乐", "这一期", "这个画面", "博主的声音", "后面那段", "开头三秒",
            "这个转场", "评论区", "这道菜", "这只猫", "这个结局", "这个创意", "最后的彩蛋", "这个机位", "这个教程", "这首歌", "这个滤镜"};
    private static final String[] PRED = {"太绝了", "真的好用心", "看了十遍", "直接封神", "有点东西", "笑到邻居敲门", "治好了我的精神内耗", "比电影还好看",
            "让我想起了小时候", "建议全网推广", "学到了", "看哭了", "一般般吧", "节奏有点慢", "没看懂", "有点水", "画质再好点就好了",
            "要是早点发就好了", "必须收藏", "已经转发给朋友了", "我妈问我为什么笑", "反复横跳", "完全戳中我", "求出续集", "求教程"};
    private static final String[] TAIL = {"", "", "", "！", "！！", "。", "~", "😂", "👍", "🔥", "😭", "[doge]", "[笑哭]", "，催更", "，关注了", "，三连了"};
    private static final String[] BAD = {"你就是个傻逼", "垃圾视频滚出去", "骗子，大家别信", "这种人怎么不去死", "诈骗团伙又来了", "操你，什么玩意"};
    private static final String[] AD = {"兼职日结300，加微信 abc12345", "想要同款的私信我", "低价代刷粉丝，vx 87654321", "点我主页领返利红包",
            "看这里 http://example.com/xx", "加v领取全套资料"};

    private Texts() {}

    public static String comment(RandomGenerator r) {
        double x = r.nextDouble();
        if (x < 0.03) return BAD[r.nextInt(BAD.length)];
        if (x < 0.06) return AD[r.nextInt(AD.length)];
        return OPEN[r.nextInt(OPEN.length)] + SUBJ[r.nextInt(SUBJ.length)] + PRED[r.nextInt(PRED.length)] + TAIL[r.nextInt(TAIL.length)];
    }
}
