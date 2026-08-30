/**
 * 剪贴板工具类
 */
class ClipboardUtil {
    /**
     * 写入内容到剪贴板
     * @param text 要写入的文本内容
     * @returns Promise<boolean> 是否成功
     */
    static async writeText(text: string): Promise<boolean> {
        try {
            // 检查浏览器是否支持 Clipboard API
            if (!navigator.clipboard) {
                console.log('Clipboard API not supported');
                return false;
            }
            // 检查权限
            if (document.visibilityState === 'hidden') {
                console.log('Document is not focused');
                return false;
            }
            await navigator.clipboard.writeText(text);
            return true;
        } catch (e) {
            console.log('写入剪贴板时发生错误，使用备用方案', e);
            // 备用方案：使用传统的 document.execCommand
            try {
                const textarea = document.createElement('textarea');
                textarea.value = text;
                textarea.style.position = 'fixed';
                textarea.style.opacity = '0';
                textarea.style.left = '-9999px';
                textarea.style.top = '-9999px';
                document.body.appendChild(textarea);
                textarea.focus();
                textarea.select();
                const success = document.execCommand('copy');
                document.body.removeChild(textarea);
                return success;
            } catch (fallbackError) {
                console.log('备用方案也失败了', fallbackError);
                return false;
            }
        }
    }

    /**
     * 从剪贴板读取文本内容
     * @returns Promise<string | null> 读取到的文本内容，失败时返回 null
     */
    static async readText(): Promise<string> {
        try {
            // 检查浏览器是否支持 Clipboard API
            if (!navigator.clipboard) {
                console.log('Clipboard API not supported');
                return '';
            }
            // 检查权限
            if (document.visibilityState === 'hidden') {
                console.log('Document is not focused');
                return '';
            }
            // 检查读取权限
            try {
                const permission = await navigator.permissions.query({
                    name: 'clipboard-read' as PermissionName,
                });
                if (permission.state === 'denied') {
                    console.log('Clipboard read permission denied');
                    return '';
                }
            } catch (permissionError) {
                // 有些浏览器可能不支持 permissions API，继续尝试读取
                console.warn('无法检查剪贴板权限，尝试直接读取', permissionError);
                return '';
            }
            return await navigator.clipboard.readText();
        } catch (e) {
            // 备用方案：提示用户手动粘贴
            console.log('读取剪贴板时发生错误', e);
            return '';
        }
    }

    /**
     * 检查是否支持剪贴板 API
     * @returns boolean
     */
    static isSupported(): boolean {
        return navigator.clipboard && window.isSecureContext;
    }

    /**
     * 检查是否支持写入剪贴板
     * @returns boolean
     */
    static isWriteSupported(): boolean {
        return navigator.clipboard && navigator.clipboard.writeText && window.isSecureContext;
    }

    /**
     * 检查是否支持读取剪贴板
     * @returns boolean
     */
    static isReadSupported(): boolean {
        return navigator.clipboard && navigator.clipboard.readText && window.isSecureContext;
    }

    /**
     * 请求剪贴板权限
     * @returns Promise<boolean> 是否获得权限
     */
    static async requestPermission(): Promise<boolean> {
        try {
            if (!navigator.permissions) {
                return true; // 如果不支持权限 API，假设有权限
            }
            const readPermission = await navigator.permissions.query({
                name: 'clipboard-read' as PermissionName,
            });
            const writePermission = await navigator.permissions.query({
                name: 'clipboard-write' as PermissionName,
            });
            return readPermission.state !== 'denied' && writePermission.state !== 'denied';
        } catch (e) {
            console.warn('无法检查剪贴板权限', e);
            return true; // 假设有权限
        }
    }
}

export default ClipboardUtil;

// 导出便捷方法
export const {
    writeText,
    readText,
    isSupported,
    isWriteSupported,
    isReadSupported,
    requestPermission,
} = ClipboardUtil;
