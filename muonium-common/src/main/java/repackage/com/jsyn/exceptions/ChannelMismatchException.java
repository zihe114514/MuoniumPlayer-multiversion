/*
 * Copyright 2010 Phil Burk, Mobileer Inc
 *
 * 依据 Apache 许可证 2.0 版（下称“许可证”）授权；
 * 除非符合许可规定，否则您不得使用此文件。
 * 您可以通过以下网址获取许可证副本：
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * 除非适用法律要求或书面同意，在许可证
 * 根据许可证分发是“按原样”分发的，
 * 在任何情况下，无论明示或默示，不提供任何担保或条件。
 * 有关许可证项下的权限，请参见许可证；
 * 许可项下的限制。
 */

package repackage.com.jsyn.exceptions;

/**
 * 例如将立体声数据入队到单声道播放器时将抛出此异常。
 * 
 * @author Phil Burk (C) 2010 Mobileer Inc
 */
public class ChannelMismatchException extends RuntimeException {

    public ChannelMismatchException(String message) {
        super(message);
    }

    /**
	 */
    private static final long serialVersionUID = -5345224363387498119L;

}
