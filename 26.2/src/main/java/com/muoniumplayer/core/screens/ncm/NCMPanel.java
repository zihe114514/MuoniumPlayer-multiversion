package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.rendering.ui.container.Panel;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/10/16 19:51
 */
public abstract class NCMPanel extends Panel {

    public abstract void onInit();

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

}
