package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/10/16 19:51
 */
public abstract class NCMPanel extends Panel {

    /**
     * 侧栏入口给这张内容页留下的语义键。它比 instanceof 更可靠：排行榜、热搜、每日推荐
     * 等页面可能复用 HomePanel 或 NeteaseDiscoveryPanel，但仍然要让同一条侧栏高亮保持连贯。
     */
    private String navigationKey;

    public abstract void onInit();

    public String getNavigationKey() {
        return this.navigationKey;
    }

    public void setNavigationKey(String navigationKey) {
        this.navigationKey = navigationKey;
    }

    protected int getColor(NCMScreen.ColorType type) {
        return 0xFF000000 | NCMScreen.getColor(type);
    }

    /**
     * 这一层是不是正在播退场动画。默认没有退场动画，所以恒为 {@code false}；
     * 带 {@link SheetClock} 的那些浮层覆盖它。
     */
    protected boolean isDismissing() {
        return false;
    }

    /**
     * 退场途中的这一层把输入整个吞掉：既不让它往下层穿透（那一下可能正落在歌曲行上、当场开始
     * 放另一首），也不留给自己（已经决定要关这一层，就不该还能点进某张卡、再弹出一个确认框）。
     *
     * <p>写在基类而不是各层自己写：这一层族里有九个浮层，其中六个走 {@code NcmSheet}、三个
     * 各自继承本类，规则必须只有一份。</p>
     */
    @Override
    public void onMouseClickReceived(double mouseX, double mouseY, int mouseButton) {
        if (isDismissing()) return;
        super.onMouseClickReceived(mouseX, mouseY, mouseButton);
    }

    /**
     * 同上，但返回 {@code false} 而不是 {@code true}：让 {@code NCMScreen} 那一串
     * 「ESC 抢在控件树之前」的分支照样把这次按键收掉，免得退场途中再按一次 ESC 穿透去关整个播放器。
     */
    @Override
    public boolean onKeyTypedReceived(char typedChar, int keyCode) {
        if (isDismissing()) return false;
        return super.onKeyTypedReceived(typedChar, keyCode);
    }

    /**
     * 内容页的滚轮兜底：指针压在页头、标题或空白处时，滚轮也应该滚这一页的主列表。
     *
     * <p>以前只有指针正好落在 {@link ScrollPanel} 矩形内才滚得动 —— 用户习惯是「在整个页面上滚滚轮」，
     * 压在页头上没反应会被当成页面卡住。这里先让正常的子控件派发跑一遍（真正的列表命中、
     * 音量条等仍优先），没人消费时再转发给本页第一个纵向滚动区。</p>
     */
    @Override
    public boolean onDWheel(double mouseX, double mouseY, int dWheel) {
        if (super.onDWheel(mouseX, mouseY, dWheel)) return true;
        if (dWheel == 0) return false;
        final ScrollPanel target = this.findPrimaryVerticalScrollPanel();
        if (target == null) return false;
        target.forwardWheel(dWheel);
        return true;
    }

    /**
     * 找本页第一个纵向滚动区。只认纵向：私人 FM 页头那排横向 chip 也是 ScrollPanel，
     * 把纵向滚轮喂给它会让「滚页头」变成横滑标签。
     */
    private ScrollPanel findPrimaryVerticalScrollPanel() {
        return findPrimaryVerticalScrollPanel(this);
    }

    private static ScrollPanel findPrimaryVerticalScrollPanel(AbstractWidget<?> host) {
        for (AbstractWidget<?> child : host.getChildren()) {
            if (child instanceof ScrollPanel) {
                ScrollPanel panel = (ScrollPanel) child;
                if (panel.getAlignment() != ScrollPanel.Alignment.HORIZONTAL) return panel;
                continue;
            }
            final ScrollPanel nested = findPrimaryVerticalScrollPanel(child);
            if (nested != null) return nested;
        }
        return null;
    }

}
