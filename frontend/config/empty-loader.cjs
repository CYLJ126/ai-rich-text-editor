// config/empty-loader.cjs
// 空 loader，将任意文件转换为空 JS 模块
// 用于让 Turbopack 跳过无法处理的文件（如含现代 CSS 语法的第三方包）
module.exports = function emptyLoader() {
    return 'module.exports = {};';
};
