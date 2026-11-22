# 属性上限解除 (attributecaplift) 2.0.0

Minecraft **1.20.1 / Forge 47.x**。

## 它解决什么

整合包用 AttributeFix 把 `minecraft:generic.movement_speed` 的上限压到 `0.5` 之后：

```
玩家实际移速      = 0.5     ← 照旧,玩家真的跑不快
疾行项链的加伤换算 = 2.23 × 1.5  ← 用"没被限制"的值
```

本模组**不修改 `RangedAttribute.maxValue`**（AttributeFix 的 0.5 原样保留），只做两件事：

1. **读值侧**：`AttributeInstance#getValue()` 对 `watchedAttributes` 里的属性返回"未 clamp"的值；
2. **移动侧**：`LivingEntity` 的移动路径里把速度压回该属性自己的 `maxValue`（0.5）。

所以"值 → 加成"的换算拿到高值，而"值 → 玩家移动"仍被限制。单人游戏（客户端+服务端同一 JVM）同样成立。

## 配置 `config/attributecaplift-common.toml`

```toml
[general]
    enabled = true
    # 这些属性在读值时返回"未 clamp 值"
    watchedAttributes = ["minecraft:generic.movement_speed"]
    # 是否在 travel 里把移速压回上限(核心开关)
    clampInTravel = true
    debugLog = false
```

> 只往 `watchedAttributes` 里加属性时，**必须确认它有对应的"作用点回落"**。目前只实现了移速的
> 移动回落；把 `max_health` / `attack_damage` 加进去会真的解除限制（本模组不会拦）。

## 实现

### 1. 读值侧 `AttributeInstanceMixin`

`AttributeInstance#getValue()`（`m_22135_`）在 `@At("RETURN")` 注入：

- 只看 `watchedAttributes`；属性不是 `RangedAttribute`、或返回值 `< maxValue`（没顶到上限）直接返回；
- 顶到上限时用 `UncappedAttributes.computeRaw()` 重算，`raw > clamped` 才替换。

`computeRaw` 与 vanilla `calculateValue()`（`m_22137_`）**逐位一致**，只去掉最后一步
`sanitizeValue()`（`Mth.clamp(min,max)`）；实测 `bitEqual=true`（见验收 RAWCHECK）。

### 2. 移动侧 `TravelCapMixin`

对 `LivingEntity` 的三处原版速度消费点做 `@Redirect`（`require = 1`，注入失败直接崩）：

| 方法（官方名 / SRG） | 消费点 | 场景 |
| --- | --- | --- |
| `getFrictionInfluencedSpeed` / `m_21330_(F)F` | `getSpeed()` | 地面行走（travel → handleRelativeFrictionAndCalculateMovement） |
| `travel` / `m_7023_(Vec3)V` | `getSpeed()` | 游泳（水分支） |
| `getFlyingSpeed` / `m_274460_()F` | `getSpeed()` | 空中/骑乘 |

`Player#getSpeed()`（`m_6113_`）本身就是 `(float) getAttributeValue(MOVEMENT_SPEED)`，
所以疾行项链读 `player.getSpeed()` 拿到的是未 clamp 的 2.23；而 travel 里同一调用被 `@Redirect`
压回 0.5。

> 1.20.1 里 `LivingEntity#getSpeed()` 只是返回 `speed` 字段；`Player` 覆盖它直接读属性。
> 地面移动的实际消费链是 `travel → handleRelativeFrictionAndCalculateMovement → getFrictionInfluencedSpeed → getSpeed()`。

## 局限（会被"漏放"的地方）

- `Player#attack` 里的 `getSpeed()`（攻击击退强度）不在移动路径里 → 用高值，击退会变强；
- `MoveControl` 写入的 `speed` 字段是未 clamp 值（但最终移动仍经过 travel 被压回）；
- 服务端 `ServerGamePacketListenerImpl` 的移动阈值读属性 → 看到高值，反作弊更宽松（不是玩法问题）；
- 骑乘（马/船等）如果覆盖了 `travel` 且不经过上面三处，不会回落；
- 创造飞行/鞘翅用 `Abilities.flyingSpeed`，本来就不吃 movement_speed；
- 梯子攀爬是固定速度，与属性无关，故无需处理；
- 其它属性（血量/攻击力）没有作用点回落 —— 不要加进 `watchedAttributes`。

## 构建

```powershell
$ws='C:\Users\hengx\Documents\deepseek-harness\default-workspace'
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'
$env:GRADLE_USER_HOME="$ws\.gradle-home"
Set-Location "$ws\attribute-cap-lift"
& "$ws\.tools\gradle-8.5\bin\gradle.bat" --offline --console=plain --no-daemon build
```

产物 `build/libs/attributecaplift-2.0.0.jar`。

## 自检（可选）

服务端加 `-Dattributecaplift.selftest=true` 会跑 `com.hengx.attributecaplift.test.SelfTest`
（100 只僵尸、分阶段测读值/位移/MSPT）。正常游戏不会触发。

## 数值面板显示（2.1.0 新增）

`AttributeInstance#getValue()` 的解除上限是**全局**的，所以神化（ApothicAttributes）的属性面板
`AttributesGui` 过去显示的是"解除后"的原始值（例如移速 2.23），而不是玩家实际生效的上限（0.5）。

2.1.0 起，面板显示**按属性修复的上限压过**的值，玩法逻辑不变（战斗/移动仍读解除后的值）：

- 新增配置 `panelClamp`（默认 `true`）：`config/attributecaplift-common.toml` → `[general] panelClamp`
- 实现：`mixin/ApothAttributesGuiClampMixin`，对 `AttributesGui` 里 8 处
  `AttributeInstance#getValue()` 调用点做 `@Redirect`（`render` / `renderEntry` / `filter` 三个方法），
  只改显示，不改 `getValue` 本身
- 可选依赖：`attributecaplift.optional.mixins.json`（`required: false` + 每处 `require = 0`），
  没装 ApothicAttributes 时静默跳过

### 2.2.0

**正式版：数值面板兼容神化（ApothicAttributes）**

* 神化属性面板与 tooltip 现在显示**按属性修复上限压过**的值（`0.5`），不再显示解除上限后的原始值；
  玩法逻辑不变（战斗/移动仍读解除后的值）。
* 覆盖 `AttributesGui` 的 8 处读值：`renderEntry`(4) / `renderTooltip`(3) / `lambda$refreshData$2`(1)。
* 注入点用 SRG 描述符 `AttributeInstance.m_22135_()D` 精确匹配；handler 为实例方法（与宿主方法修饰符一致）。
* 新增配置 `panelClamp`（默认 `true`），关掉即恢复显示原始值。
### 2.1.0
* 新增配置 panelClamp（默认 true）：神化（ApothicAttributes）的属性面板按「属性修复的上限」显示，
  不再显示解除上限后的原始值；玩法逻辑不变，仍使用解除后的值。
