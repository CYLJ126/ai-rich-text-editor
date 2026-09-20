export interface MathSymbolItem {
  latex: string;
  description: string;
}

export interface MathSymbolSection {
  key: string;
  title: string;
  symbols: MathSymbolItem[];
}

const parseSymbols = (source: string): MathSymbolItem[] =>
  source
    .trim()
    .split('\n')
    .map((line) => {
      const [latex, description] = line.split('|');
      return {latex: latex.trim(), description: description.trim()};
    });

const createSection = (
  key: string,
  title: string,
  source: string,
): MathSymbolSection => ({
  key,
  title,
  symbols: parseSymbols(source),
});

export const MATH_SYMBOL_SECTIONS: MathSymbolSection[] = [
  createSection(
    'expressions',
    '表达式',
    String.raw`
a\,b|很窄的正间距（\, 3/18 em；\: 4/18 em，\; 5/18 em；\quad 1 em；\qquad 2 em；）
a\!b|负的窄间距，用于收紧内容
\frac{a}{b}|分数
x^2|上标；多个字符应写成 x^{n+1}
x_2|下标；多个字符应写成 x_{i+1}
x_i^2|同时使用上标和下标
\sqrt{2}|平方根
\sqrt[n]{x}|n 次根
f(x)|函数记号
\frac{\partial f}{\partial x}|偏导数
\binom{n}{k}|二项式系数
\overbrace{a+b}^{n}|上花括号及说明
\underbrace{a+b}_{n}|下花括号及说明
\text{当 }x>0|在数学公式中插入普通文字
\left(\frac{x}{y}\right)|让界定符随内容自动伸缩
\begin{matrix}a & b \\ c & d\end{matrix}|无括号矩阵；& 分列，\\ 换行
\begin{pmatrix}a & b \\ c & d\end{pmatrix}|圆括号矩阵
\begin{bmatrix}a & b \\ c & d\end{bmatrix}|方括号矩阵
\begin{cases} x, & x\ge 0 \\ -x, & x<0 \end{cases}|左侧花括号的分段表达式
\left.\begin{array}{l} x=1 \\ y=2 \end{array}\right\}|右侧花括号
\begin{aligned} a&=b+c \\ &=d \end{aligned}|多行对齐公式
\begin{cases} \frac{n}{2}, & n\text{ 为偶数} \\[2ex] 3n+1, & n\text{ 为奇数} \end{cases}|带额外行距的分段表达式`,
  ),
  createSection(
    'arithmetic',
    '算术/大型运算',
    String.raw`
+|加法
-|减法或负号
\pm|正负号
\mp|负正号，常与 \pm 配对使用
\times|乘号或笛卡尔积
\div|除号
\cdot|点乘号
\ast|星号运算符
\circ|复合运算或小圆运算符
\setminus|差集；比 \backslash 更适合作为二元运算符
\sum_{i=1}^{n}|求和符号
\prod_{i=1}^{n}|连乘符号
\coprod_{i=1}^{n}|余积符号
\min S|最小值
\max S|最大值`,
  ),
  createSection(
    'relation',
    '关系运算',
    String.raw`
=|等于
\ne|不等于，也可输入 \neq
<|小于
>|大于
\le|小于或等于，也可输入 \leq
\ge|大于或等于，也可输入 \geq
\ll|远小于
\gg|远大于
\approx|近似等于
\sim|相似、同阶或服从某分布
\simeq|渐近相等或近似相等
\cong|全等或同构
\equiv|恒等、等价或同余
\propto|正比于
\perp|垂直；在逻辑中也可表示矛盾或假
\parallel|平行
\mid|整除或“满足……条件”
\nmid|不整除`,
  ),
  createSection(
    'logic',
    '逻辑运算',
    String.raw`
\neg|逻辑非
\land|逻辑与，也可输入 \wedge
\lor|逻辑或，也可输入 \vee
\oplus|异或；也可表示直和
\implies|推出
\iff|当且仅当
\forall|对所有、任意
\exists|存在
\nexists|不存在
\top|真
\bot|假或矛盾
\therefore|所以
\because|因为`,
  ),
  createSection(
    'logarithm',
    '对数/指数',
    String.raw`
\log x|对数；底数未注明时由上下文约定
\log_b x|以 b 为底的对数
\ln x|自然对数
\lg x|常用对数，在部分中文教材中表示以 10 为底
\exp(x)|指数函数
e^x|自然指数函数的常见写法`,
  ),
  createSection(
    'trigonometry',
    '三角函数与角',
    String.raw`
30^\circ|角度，例如 30 度
\angle A|角
\measuredangle A|有向角或测量角
\triangle ABC|三角形
\sin x|正弦
\cos x|余弦
\tan x|正切
\cot x|余切
\sec x|正割
\csc x|余割
\arcsin x|反正弦
\arccos x|反余弦
\arctan x|反正切
\sinh x|双曲正弦
\cosh x|双曲余弦
\tanh x|双曲正切`,
  ),
  createSection(
    'calculus',
    '微积分运算符',
    String.raw`
\int|积分
\int_a^b f(x)\,dx|定积分
\iint|二重积分
\iiint|三重积分
\oint|闭合曲线积分
\partial|偏微分符号
\frac{d}{dx}|对 x 求导
f'(x)|一阶导数
f''(x)|二阶导数
\lim_{x\to a} f(x)|x 趋于 a 时的极限
\limsup|上极限
\liminf|下极限
\infty|无穷大
\nabla|Nabla 算子；用于梯度、散度和旋度
\nabla f|标量场 f 的梯度
\nabla\cdot\mathbf{F}|向量场 F 的散度
\nabla\times\mathbf{F}|向量场 F 的旋度`,
  ),
  createSection(
    'set',
    '集合运算',
    String.raw`
\emptyset|空集
\varnothing|空集的另一种常见字形
x\in A|x 属于集合 A
x\notin A|x 不属于集合 A
A\ni x|集合 A 包含元素 x
A\subset B|A 是 B 的真子集；部分文献也用它表示一般子集
A\subseteq B|A 是 B 的子集或等于 B
A\subsetneq B|A 是 B 的真子集，含义明确
A\supset B|A 是 B 的真超集；部分文献也用它表示一般超集
A\supseteq B|A 是 B 的超集或等于 B
A\supsetneq B|A 是 B 的真超集，含义明确
A\nsubseteq B|A 不是 B 的子集
A\cap B|交集
A\cup B|并集
A\setminus B|差集
A\triangle B|对称差
A\uplus B|不交并（互斥并）
A^{\complement}|补集
\mathcal{P}(A)|集合 A 的幂集
\mathbb{N}|自然数集
\mathbb{Z}|整数集
\mathbb{Q}|有理数集
\mathbb{R}|实数集
\mathbb{C}|复数集`,
  ),
  createSection(
    'greek',
    '希腊字母',
    String.raw`
A|Alpha（大写，与拉丁字母 A 同形）
B|Beta（大写，与拉丁字母 B 同形）
\Gamma|Gamma（大写）
\Delta|Delta（大写）
E|Epsilon（大写，与拉丁字母 E 同形）
Z|Zeta（大写，与拉丁字母 Z 同形）
H|Eta（大写，与拉丁字母 H 同形）
\Theta|Theta（大写）
I|Iota（大写，与拉丁字母 I 同形）
K|Kappa（大写，与拉丁字母 K 同形）
\Lambda|Lambda（大写）
M|Mu（大写，与拉丁字母 M 同形）
N|Nu（大写，与拉丁字母 N 同形）
\Xi|Xi（大写）
O|Omicron（大写，与拉丁字母 O 同形）
\Pi|Pi（大写）
P|Rho（大写，与拉丁字母 P 同形）
\Sigma|Sigma（大写）
T|Tau（大写，与拉丁字母 T 同形）
\Upsilon|Upsilon（大写）
\Phi|Phi（大写）
X|Chi（大写，与拉丁字母 X 同形）
\Psi|Psi（大写）
\Omega|Omega（大写）
\mho|Mho，导纳单位，大写 Ω 的倒置形式（大写）
\digamma|Digamma，双伽玛（大写）
Ϛ|Stigma，Digamma 的另一种变体（大写）
Ϙ|Koppa（大写）
ϟ|Koppa 的另一种变体（大写）
Ϻ|San（大写）
Ϡ|Sampi，超 iota（大写）
\alpha|alpha
\beta|beta
\gamma|gamma
\delta|delta
\epsilon|epsilon
\zeta|zeta
\eta|eta
\theta|theta
\iota|iota
\kappa|kappa
\lambda|lambda
\mu|mu
\nu|nu
\xi|xi
o|omicron（与拉丁字母 o 同形）
\pi|pi
\rho|rho
\sigma|sigma
\tau|tau
\upsilon|upsilon
\phi|phi
\chi|chi
\psi|psi
\omega|omega
\varepsilon|epsilon 的变体
\vartheta|theta 的变体
\varkappa|kappa 的变体；部分渲染器可能不支持
\varpi|pi 的变体
\varrho|rho 的变体
\varphi|phi 的变体
\varsigma|词尾 sigma`,
  ),
  createSection(
    'accents',
    '戴帽/修饰',
    String.raw`
\hat{x}|单字符尖帽
\widehat{xyz}|多字符宽尖帽
\bar{x}|单字符上横线
\overline{xyz}|多字符上横线
\tilde{x}|单字符波浪号
\widetilde{xyz}|多字符宽波浪号
\vec{v}|向量箭头
\overrightarrow{AB}|从 A 指向 B 的长箭头
\overleftarrow{AB}|从 B 指向 A 的长箭头
\dot{x}|上点；常表示对时间的一阶导数
\ddot{x}|双上点；常表示对时间的二阶导数
\acute{x}|尖音符
\grave{x}|重音符
\breve{x}|短音符
\check{x}|抑扬符
x'|撇号；常表示导数
x''|双撇号`,
  ),
  createSection(
    'arrows-delimiters',
    '箭头/界定',
    String.raw`
\leftarrow|左箭头，也可输入 \gets
\rightarrow|右箭头，也可输入 \to
\leftrightarrow|左右箭头
\Leftarrow|左双线箭头
\Rightarrow|右双线箭头
\Leftrightarrow|左右双线箭头
\mapsto|映射到
\uparrow|上箭头
\downarrow|下箭头
\nearrow|右上箭头
\searrow|右下箭头
(x)|圆括号
[x]|方括号
\{x\}|花括号；需转义
\langle x\rangle|尖括号或内积记号
\lvert x\rvert|绝对值
\lVert x\rVert|范数或双竖线
\lfloor x\rfloor|下取整
\lceil x\rceil|上取整
\left(\frac{a}{b}\right)|自动调整大小的圆括号`,
  ),
  createSection(
    'other-symbols',
    '其他数学符号',
    String.raw`
\%|百分号
\prime|素数或角分符号，具体含义依语境而定
\prime\prime|角秒或双撇号
\ldots|基线省略号
\cdots|居中省略号
\vdots|竖直省略号
\ddots|斜向省略号
\aleph|阿列夫数
\hbar|约化普朗克常数
\ell|手写体小写 l
\Re z|复数的实部
\Im z|复数的虚部
\oplus|直和或异或
\otimes|张量积
\odot|圆点运算符
\bigoplus|大型直和运算符
\bigotimes|大型张量积运算符
\bigodot|大型圆点运算符
\bullet|实心圆点运算符
\star|星形运算符
\dagger|匕首号；也常表示共轭转置
\ddagger|双匕首号
\arg z|复数 z 的辐角
\gcd(a,b)|最大公约数`,
  ),
];
