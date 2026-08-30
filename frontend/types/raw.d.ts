/** 支持 ?raw 后缀导入文件为字符串 */
declare module "*?raw" {
    const content: string;
    export default content;
}

/** 支持 ?inline 后缀导入文件为字符串（备用） */
declare module "*?inline" {
    const content: string;
    export default content;
}
