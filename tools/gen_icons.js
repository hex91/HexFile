/**
 * 从 tools/icon-source.svg 生成 Android 启动图标。
 *
 * 输出（覆盖 res/ 下同名文件）：
 *   mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi  ic_launcher.png            48/72/96/144/192
 *                                          ic_launcher_round.png      同上，圆形裁切
 *                                          ic_launcher_foreground.png 108/162/216/324/432
 *
 * 为什么 foreground 要单独缩小：自适应图标只显示中心 72/108 的画布，安全区是 66/108 的圆。
 * 源图前景（文件夹 + HF）横向已占 628/1024，四角落在圆外，直接拿来当 foreground 会被
 * 系统蒙版切掉角。这里统一缩到 0.80 并以内容包围盒中心（512,538）对齐画布中心。
 *
 * 依赖：@resvg/resvg-js。本机没有全局安装，用 NODE_PATH 指向已有 node_modules 即可：
 *   NODE_PATH=/c/Users/<user>/.../node_modules node tools/gen_icons.js
 * 或者在任意目录 `npm i @resvg/resvg-js` 后，在本仓库根执行上面的命令。
 */
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(__dirname, 'icon-source.svg');
const svg = fs.readFileSync(SRC, 'utf8');

const DPI = [
  ['mdpi', 48, 108],
  ['hdpi', 72, 162],
  ['xhdpi', 96, 216],
  ['xxhdpi', 144, 324],
  ['xxxhdpi', 192, 432],
];

const defs = svg.match(/<defs>[\s\S]*?<\/defs>/)[0];
const fgStart = svg.indexOf('<rect x="232" y="356"');
const fgEnd = svg.lastIndexOf('</svg>');
if (fgStart < 0 || fgEnd < 0) throw new Error('icon-source.svg 结构变了：找不到前景起点或 </svg>');
const foreground = svg.slice(fgStart, fgEnd);

// 自适应图标 foreground：透明底 + 内容缩到安全区
const FG_SCALE = 0.80;
const fgSvg =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">\n' +
  defs +
  '\n<g transform="translate(512 512) scale(' + FG_SCALE + ') translate(-512 -538)">\n' +
  foreground +
  '</g>\n</svg>\n';

// 圆形版：把圆角矩形裁切换成圆，并去掉那圈方形描边
const roundSvg = svg
  .replace(
    /<clipPath id="round">[\s\S]*?<\/clipPath>/,
    '<clipPath id="round"><circle cx="512" cy="512" r="512"/></clipPath>'
  )
  .replace(/<rect x="2" y="2" width="1020" height="1020"[^>]*\/>\s*/, '');

function render(source, size, out) {
  const png = new Resvg(source, { fitTo: { mode: 'width', value: size } }).render().asPng();
  fs.writeFileSync(out, png);
  return png.length;
}

let total = 0;
for (const [dpi, iconPx, fgPx] of DPI) {
  const dir = path.join(ROOT, 'res', 'mipmap-' + dpi);
  fs.mkdirSync(dir, { recursive: true });
  render(svg, iconPx, path.join(dir, 'ic_launcher.png'));
  render(roundSvg, iconPx, path.join(dir, 'ic_launcher_round.png'));
  render(fgSvg, fgPx, path.join(dir, 'ic_launcher_foreground.png'));
  total += 3;
  console.log(dpi + ': icon=' + iconPx + ' round=' + iconPx + ' foreground=' + fgPx);
}
console.log('已生成 %d 个 PNG', total);
