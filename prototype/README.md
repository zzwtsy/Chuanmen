# Chuanmen 交互原型

## 打开与范围

在仓库中用现代 Chrome、Edge、Firefox 或 Safari 打开 [index.html](index.html)。页面引用同目录的 `assets/`，移动或导出时保留资源目录；所需 D3 和 Lucide 已固定为本地资源。

原型展示模拟设备、流量和日志，刷新恢复演示初始状态。真实 VPN、设备发现与核心配置解析由 Android 产品工程实现。复制结果受浏览器剪贴板权限影响。

## 建议走查顺序

1. 总览查看合并后的虚拟网络卡片、当前上下行和固定 60 秒曲线；确认没有流量详情或时间范围控件。
2. 打开网络选择器并添加配置；给本地配置取名，或留空观察回退到网络名称。
3. 到配置页切换“基础配置 / 完整配置”，输入错误地址再切换标签，观察输入与错误保留。
4. 点击配置标题旁的重命名，观察核心配置和待应用状态保持不变。
5. 修改配置后仅保存，观察运行 IP 不会先变化；运行差异通过底部共享操作区应用。
6. 断开后保存，观察离线已保存没有常驻“配置已应用”横条。
7. 在设备页展开条目并查看详情，检查 NAT、版本、收发、探测丢包和一致的 IP 复制入口。
8. 在日志页切换来源与筛选，停留历史记录时观察新增提示；在设置中调整当前网络流量范围。

## 源文件与重建

| 文件 | 职责 |
|---|---|
| `source/config-ux-shell.html` | 页面框架、状态与公共交互模板 |
| `source/config-ux-config.js` / `.css` | 配置表单与交互样式 |
| `source/focused-monitor.js` / `.css` | 流量和日志模块 |
| `source/chuanmen-android-config.fragment.html` | 由可编辑源组装的原型片段 |
| [配置快照](../docs/design/config/assistant-catalog.snapshot.json) | 实际注入的冻结编辑目录 |
| [资源清单](assets/manifest.json) | 离线依赖的版本、URL、大小和 SHA-256 |

从仓库根运行：

```bash
python3 scripts/prototype/export_prototype.py
```

导出器读取可编辑源、冻结配置目录和离线资源，更新片段及独立页面。修改源文件后重建，检查生成差异，再按交互风险走查相关场景与脚本语法；不要只改生成页面。

根 `SHA256SUMS` 只覆盖 P00 阶段运输材料，原型修改不刷新这份清单。原型历史摘要和验证对象由 [历史基线](../docs/verification/prototype-baseline.json) 定位。新增验证记录应保存新的输入身份与范围，原记录保留原样。

## 历史验证与后续验收

历史基线记录命名前的交互 QA、名称调整、导出复建和脚本检查。其中基础 DOM/事件/计时器检查在前一候选运行，后续检查另覆盖离线保存边界和语法结构；各项结果只适用于记录中的输入哈希。

浏览器像素、Android 触控与焦点、系统动态色、TalkBack、原生对话框及真实网络须按对应风险单独验证。进入原生实施后，使用 P03/P04/P06 的设备与可访问性门槛；阶段状态见 [路线图](../docs/plan/ROADMAP.md)。

## 第三方资源

- D3 7.9.0：使用 [ISC 许可副本](assets/d3-LICENSE.txt)，来源 [d3 官方仓库](https://github.com/d3/d3)。
- Lucide 1.17.0：使用 [许可副本](assets/lucide-LICENSE.txt)，其中包含所继承图标来源的声明；来源 [Lucide 官方仓库](https://github.com/lucide-icons/lucide)。

资源更新须核对来源、许可和资源清单。Android 应用的依赖与图标实现由自身构建配置和设计系统决定。
