# Java 代码规范

## 目录

- [语言与格式](#语言与格式)
- [注解排版](#注解排版)
- [Java 链式调用](#java-链式调用)
- [参数换行](#参数换行)
- [Java 卫语句](#java-卫语句)
- [对象构造](#对象构造)
- [Builder 使用](#builder-使用)
- [Optional 使用](#optional-使用)
- [null 与空值](#null-与空值)
- [空判断与防御代码](#空判断与防御代码)
- [命名](#命名)
- [集合与可变性](#集合与可变性)
- [Java 导入](#java-导入)
- [注释](#注释)
- [异常与错误信息](#异常与错误信息)
- [兜底与失败处理](#兜底与失败处理)
- [实现选择](#实现选择)
- [测试代码](#测试代码)

## 语言与格式

- Java 格式化以 IntelliJ IDEA 内置格式为准；构建默认不替代 IDE 格式化检查。

## 注解排版

- 注解只有一个简单参数时，在所在类或同一组声明没有采用统一多行风格的情况下可以保持单行，例如 `@MenuButton(slot = SLOT)`。
- 同一类或同一组声明中，如果大多数同类注解参数采用多行格式，则同类注解统一全部展开为多行，即使只有一个或两个参数也不保留单行写法。展开时注解名与左括号同行，每个参数独占一行，右括号单独一行并与注解的 `@` 缩进对齐。
- 注解应用于字段、record component 或其他成员声明时，右括号后必须换行再书写类型和变量名；不要把声明直接接在 `)` 后面。
- 数组参数的元素可以保持在同一参数行，除非超过项目行宽或继续保持可读性需要换行。

~~~java
@MenuAction(
        slot = REFRESH_ENABLED_SLOT,
        clicks = {ClickType.LEFT, ClickType.RIGHT},
        playClickSound = true
)
~~~

当注解修饰字段或 record component 时，声明成员另起一行，例如：

~~~java
@ConfigValue(
        path = "selection.stick-material",
        defaultValue = "STICK",
        nonBlank = true
)
String stickMaterial,
~~~

## Java 链式调用

- 对于 `ItemBuilder`、`Component builder`、`Stream` 风格等链式调用，如果链条包含多个步骤，不要压成单行。
- Java 格式化以 IntelliJ IDEA 内置格式化为准，`./gradlew build` 不再执行格式校验。
- 推荐写成首行保留创建表达式，后续每个链式方法单独换行，例如：

```java
ItemStack lockedSlot = ItemBuilder.of(Material.STAINED_GLASS_PANE)
                .durability(14)
                .name("&c锁定中")
                .lore("&7前面的区域, 以后再来探索吧!")
                .build();
```

- 避免写成这种单行链式风格：

```java
ItemStack lockedSlot = ItemBuilder.of(Material.STAINED_GLASS_PANE).durability(14).name("&c锁定中")
                .lore("&7前面的区域, 以后再来探索吧!").build();
```

- 目标是让每一步调用在视觉上清晰可扫读，减少长表达式横向堆叠。
- 链式调用中的单个方法如果只有 `1` 到 `2` 个短参数，不需要为了参数换行规则再拆开；优先保持链条步骤清晰。

## 参数换行

- 参数换行以可读性优先，而不是只按参数数量机械判断。
- 当方法声明、构造器声明或方法调用的小括号内参数超过 `2` 个时，默认推荐改为多行；但如果调用参数、声明里的类型和变量名都很短，单行仍然清晰，则允许保持单行。
- 当方法调用只有 `1` 到 `2` 个短参数时，推荐保持紧凑，不需要为了参数换行规则拆开。
- 判断实参是否“短且清晰”时，也要考虑 IntelliJ IDEA 可能显示的形参名提示；如果形参名加上实参后整体过长或难以扫读，仍然推荐换行。
- 当任意参数表达式较长、包含 lambda/匿名类/嵌套调用、连续布尔值或 `null` 等语义不明显的值，或单行可读性明显下降时，应改为多行。
- 多行参数列表中，每个参数单独占一行。
- 结尾的 `)` 单独一行，并与语句缩进对齐。
- **方法声明与构造器声明一旦换行，必须采用“每个参数单独一行”的格式**：方法名或 `new` 表达式加 `(` 独占首行，之后每个参数各占一行（参数缩进为声明缩进 + 8 空格），结尾 `)` 单独一行与声明开头对齐。即使参数很短，只要声明换行，就不能把多个参数挤在同一行。
- **方法调用换行没有“必须每个参数一行”的硬性要求**：调用点以可读性优先，短参数可以多个同行；只有参数较长、含嵌套调用或语义不明显时才每个参数单独一行。
- 若本规则与“超过 `2` 个参数换行”的旧约定冲突，以“短且清晰可单行，长或复杂才多行”为准。
- 这条规则目前作为团队约定执行，不由 Gradle 自动检查。

例如两个短参数的方法调用推荐保持紧凑：

```java
String skinName = section.getString(internalName, "").trim();
ItemStack itemStack = this.resolveUsedItem(event, player);
if (!this.pitItemUseDebounceService.tryUse(player, GoldenHeadPerk.GOLDEN_HEAD_ITEM_ID)) return;
```

例如短类型、短变量名的声明即使超过两个参数，也可以保持单行：

```java
private int clamp(int value, int min, int max) {
```

但只要参数本身变长或语义不容易扫读，就推荐换成多行：

```java
CombatDamageEvent event = new CombatDamageEvent(
        attacker,
        target,
        DamageRequest.builder()
                .sourceType(CombatSourceType.PLAYER)
                .damageType(DamageType.DEFAULT)
                .build()
);
```

方法声明一旦换行，必须“每个参数单独一行”，不能把多个参数挤在首行或同行：

```java
// 正例：方法名 + ( 独占首行，每个参数一行，结尾 ) 与声明对齐
public static ArrayList<Vec3> computePath(
        final Vec3 startVec3,
        final Vec3 endVec3,
        final HashSet<Long> excludedKeys
) {
```

```java
// 反例：首行带参数、多参数挤在同一行，均不符合规范
public static ArrayList<Vec3> computePath(final Vec3 startVec3, final Vec3 endVec3,
        final HashSet<Long> excludedKeys) {
```

方法调用换行则没有“每个参数一行”的硬性要求，短参数可以多个同行：

```java
// 正例：调用点短参数多个同行，可读性优先
final PathNode startNode = new PathNode(
        start.getX(), start.getY(), start.getZ(),
        heuristic(start.getX(), start.getY(), start.getZ())
);
```

## Java 卫语句

- 对于简单的提前返回卫语句，推荐省略大括号并写成单行，例如：

```java
if (player == null) return;
if (implementationType == null) return null;
```

- 适用范围限于短的控制流语句：`return`、`return value`、`continue`、`break`、`throw`。
- 如果条件或返回表达式较长、包含注释、存在 `else`、或单行后可读性下降，则保留多行大括号。
- 长的复合卫语句通常优先拆成多个短卫语句；只有当多个条件共同表达同一个业务概念时，才优先提取具名方法或具名布尔变量。

多个独立条件推荐拆开：

```java
if (user == null) return;
if (!user.isActive()) return;
if (!permissionService.canAccess(user, resource)) return;
```

如果复合条件本身表达一个整体业务概念，可以提取具名方法：

```java
if (!canAccessResource(user, resource)) return;
```

- 编写新代码或修改旧代码时，优先使用卫语句减少不必要的嵌套，但不要为了形式统一强行重构清晰的业务分支。
- 当 `if/else` 用来表达成功/失败、允许/拒绝、命中/未命中等对称业务分支时，可以保留分支结构；业务语义清晰优先于形式上的卫语句化。

## 对象构造

- 当构造器、record 或静态工厂的参数超过 `5` 到 `6` 个，尤其出现连续的 `true`、`false`、`0`、`null` 等难以读出含义的参数时，优先考虑增加命名静态工厂、参数对象或 builder。
- 不为了追求模式统一而给简单对象增加额外抽象；只有当命名能明显改善调用点可读性时再引入。
- 当构造器、静态工厂或 `this(...)` 委托参数超过 `6` 个，且混有多个同类型参数、函数式参数、数字常量或可选行为时，不应继续堆叠新的重载链；优先收敛为参数对象、record、builder 或更明确的命名静态工厂。
- 命名静态工厂应表达业务场景，而不是只复述参数形状，例如优先使用 `singleLevel(...)`、`scalableWithFixedUpgradeCost(...)`、`scalableWithLevelCost(...)` 这类名称，而不是继续增加只差一两个参数的构造器。
- 多个重载委托到核心构造器时，避免在 `this(...)` 调用里直接写含义不明显的默认值、`null`、`0`、`20` 或 `ignored -> requiredRenown` 这类 lambda；如果暂时不引入参数对象，也应先用局部变量或具名方法说明语义。
- 当某个参数可能同时被理解为“首级价格”“升级价格”“每级价格”等不同业务含义时，必须拆分命名，例如 `firstLevelRenown`、`upgradeRenown`、`requiredRenownByLevel`，避免只用 `requiredRenown` 承载多种语义。
- 对于可选行为，优先使用空对象或具名默认方法表达默认值，例如 `DamageEffect.none()`、`TickEffect.none()`；但如果默认值会影响价格、等级、权限、消耗材料等核心规则，应通过命名工厂或参数对象集中表达，避免散落在长参数列表中。

## Builder 使用

- Builder 主要用于字段较多、可选参数较多、构造语义复杂的对象。
- 不为了统一风格给简单对象强行增加 Builder。
- 对于只有少量字段、语义清晰的对象，优先使用构造器、record 或静态工厂。
- Builder 的 `build()` 方法应负责必要校验，避免创建非法对象。
- 如果必填字段很多，优先考虑构造器、静态工厂、参数对象或 staged builder 来表达必填约束。

## Optional 使用

- `Optional` 推荐用于方法返回值，用来表达“可能没有结果”。
- 不推荐把 `Optional` 作为字段类型、方法参数类型或 builder 字段类型。
- 集合类型优先返回空集合，而不是 `Optional<List<T>>`、`Optional<Set<T>>`。
- 避免直接使用 `optional.get()`；优先使用 `orElse`、`orElseGet`、`orElseThrow` 或显式分支。
- 当缺失值属于异常情况时，使用 `orElseThrow()` 并提供清晰的异常信息。

## null 与空值

- 方法返回集合或数组时，优先返回空集合或空数组，而不是 `null`。
- 只有当 `null` 具有明确业务语义时，才允许返回或传递 `null`。
- 对外暴露的 API 如果可能返回 `null`，应通过注解、文档或方法命名明确表达。
- 对于可选行为，优先使用具名默认值、空对象或默认实现，而不是散落的 `null`。
- 避免在长参数列表中直接传递多个 `null`，应改用参数对象、builder 或具名变量说明含义。

## 空判断与防御代码

- 避免为了“保险”添加无意义的 `null`、空集合、空字符串判断。
- 已由上游校验、构造器约束、注解或生命周期保证的值，不需要重复防御。
- 空判断应对应明确业务分支、兼容边界或外部输入边界。
- 对不应发生的状态，优先显式失败，而不是静默返回或继续执行。
- 连续多层空判断导致主流程不清晰时，优先收敛校验入口或拆出具名方法。

## 命名

- 命名应优先表达业务含义，而不是类型或实现细节。
- 避免使用过于宽泛的名称，例如 `data`、`info`、`obj`、`temp`、`manager`。
- 布尔变量和布尔方法优先使用 `is`、`has`、`can`、`should`、`allow` 等前缀表达语义。
- 避免使用否定布尔名，例如 `notFound`、`notAllowed`，除非它本身就是业务概念。
- 涉及数量、时间、大小、距离、金额等值时，变量名应尽量体现单位或语义，例如 `timeoutMillis`、`durationSeconds`、`maxRetries`。

## 集合与可变性

- 对外暴露集合时，避免直接返回内部可变集合。
- 如果调用方不应该修改返回结果，优先返回不可变集合、不可变视图或防御性副本。
- 方法返回集合时，优先返回空集合，而不是 `null`。
- 不要假设调用方不会修改传入的集合；必要时应复制一份再保存。
- 对于不会变化的常量集合，优先使用不可变集合表达意图。

## Java 导入

- 尽量避免在正文代码中直接书写类似 `cn.mythicland.mythicthepit.api.EnchantApi enchantApi` 这样的全限定名类型声明。
- 优先通过 `import` 导入类型后再直接使用简单类名，例如写成 `EnchantApi enchantApi`。
- 只有在确实需要消除同名类型冲突，或局部使用全限定名能明显降低歧义时，才保留全限定名写法。

## 注释

- 注释优先解释“为什么这样做”，而不是重复代码“做了什么”。
- 对于临时兼容、历史遗留、特殊边界、性能折中，应写明原因。
- 如果代码需要大量注释才能读懂，优先考虑改命名、拆方法或调整结构。
- TODO 应写明后续动作，避免只写 `TODO fix`。

## 异常与错误信息

- 异常信息应包含足够定位问题的上下文。
- 不要吞掉异常；如果确实需要忽略，应通过注释说明原因。
- 捕获异常时，优先捕获具体异常类型，避免无意义地捕获 `Exception`。
- 抛出 `IllegalArgumentException`、`IllegalStateException` 等异常时，信息应说明哪个参数或状态不合法。
- 不要在异常信息中泄露密码、Token、密钥等敏感信息。

## 兜底与失败处理

- 避免写无明确业务语义的默认值、默认分支或 catch-all 兜底。
- 缺失配置、未知类型、非法状态、未支持分支，优先抛出带上下文的异常。
- 只有当业务明确允许降级、跳过或默认处理时，才写兜底逻辑。
- 兜底逻辑应可定位原因，必要时记录上下文或集中处理。
- 不用空实现、空返回、默认对象掩盖数据错误或流程错误。

## 实现选择

- 优先使用 JDK、项目已有工具类或成熟依赖提供的稳定实现。
- 避免为常见能力重复手写不完整实现。
- 避免使用实验性、临时性或行为不稳定的实现作为核心路径。
- 当简单直接的实现已经足够清晰时，不为了“可扩展”提前引入复杂抽象。
- 新增抽象应服务于真实重复、清晰边界或可测试性。

## 测试代码

- 测试名、局部变量名和断言应尽量表达业务含义，避免只留下无法解释意图的魔法值。
- 当断言使用固定数字、字符串或物品数量时，优先用变量名或辅助方法说明“为什么是这个值”。
- 测试失败消息应说明业务期望，而不是只显示内部实现细节。
- 测试必须验证可观察行为和失败边界，不只验证私有方法的当前写法。
