package top.tankenqi.zingdb.client.ui;

/**
 * REPL 提示符状态机。
 *
 *   IDLE         "zingdb> "         灰色，普通待输入
 *   IN_TX        "zingdb* "         黄色 *，表示当前在事务里
 *   AFTER_ERROR  "zingdb! "         红色 !，上一条出错；下一次成功后自动回到 IDLE
 *   CONTINUE     "      … "         续行（多行 SQL 时的二级提示）
 */
public final class Prompter {

    public enum State { IDLE, IN_TX, AFTER_ERROR }

    private State state = State.IDLE;

    public State state() { return state; }
    public void setState(State s) { this.state = s; }

    /** 用户事务状态变更后调用：传入 inTx；如果传 false 而当前是 AFTER_ERROR，则保留 AFTER_ERROR。 */
    public void onResult(boolean inTx, boolean error) {
        if (error) { state = State.AFTER_ERROR; return; }
        state = inTx ? State.IN_TX : State.IDLE;
    }

    public String mainPrompt() {
        switch (state) {
            case IN_TX:       return Theme.warn("zingdb*") + " " + Theme.muted(Theme.arrow() + " ");
            case AFTER_ERROR: return Theme.danger("zingdb!") + " " + Theme.muted(Theme.arrow() + " ");
            case IDLE:
            default:          return Theme.brand("zingdb") + " " + Theme.muted(Theme.arrow() + " ");
        }
    }

    /** 多行续行提示符。 */
    public String continuePrompt() {
        return Theme.muted("       " + Theme.ellipsis() + " ");
    }
}
