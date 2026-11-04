package com.example.ticket.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 重试策略。
 *
 * 只决定「要不要再试一次」，不决定怎么试、也不碰模型。
 *
 * 三个设计决定：
 *
 * 1. 只在有结构违规时重试。
 *    值本身为 null 不是违规（那是信息缺失），不重试——
 *    模型修不好的事，重试只是浪费钱。
 *
 * 2. 重试带「纠错提示」，不是盲目重试。
 *    把上一次的违规项反馈回去，比再问一遍有效得多。
 *
 * 3. 有上限，且上限之后不再纠缠，直接降级。
 *    降级后的结果是「全员缺失」，由核验层判红——
 *    也就是说，模型彻底失败时系统仍然能产出一张
 *    诚实的、信息不全的工单，而不是抛异常给用户。
 */
@Component
public class RetryPolicy {

    /** 总尝试次数：首次 + 2 次重试 */
    private final int maxAttempts;

    public RetryPolicy(@Value("${app.extraction.max-attempts:3}") int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * @param attempt 刚刚完成的第几次尝试（从 1 开始）
     */
    public boolean shouldRetry(int attempt, ValidationResult result) {
        return result.hasViolations() && attempt < maxAttempts;
    }
}
