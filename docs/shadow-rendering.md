# الظلال في MY SPACE

## كيف تُنفّذ عادةً

في محركات ثلاثية الأبعاد، الطريقة العامة للظلال الديناميكية هي **Shadow Mapping**: يُرسم المشهد من منظور الضوء إلى خريطة عمق، ثم تُقارن الأعماق عند رسم الكاميرا لمعرفة ما إذا كان سطح ما محجوبًا. للحصول على حافة ناعمة تُؤخذ عدة مقارنات قريبة وتُحسب نسبة الضوء المارّة؛ تُعرف هذه الطريقة باسم **Percentage-Closer Filtering (PCF)**. زيادة عدد العينات توسّع وتنعّم شبه الظل، لكنها ترفع كلفة كل بكسل، لذلك تُحصر عادةً أو تُكيّف حسب الحاجة. [GPU Gems: PCF and shadow-map antialiasing](https://developer.nvidia.com/gpugems/gpugems/part-ii-lighting-and-shadows/chapter-11-shadow-map-antialiasing) [GPU Gems 2: adaptive soft shadows](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-17-efficient-soft-edged-shadows-using)

على الهاتف، لا تكفي جودة الصورة وحدها؛ يجب ضبط عدد الطبقات الشفافة وإعادة استخدام الأشكال والموارد. توثيق Android يحذّر من كلفة إعادة إنشاء المسارات والـbitmaps ومن الإفراط في الرسم المتراكب، كما يوصي باختبار الرسم المخصص على أجهزة فعلية لأن دعم وتسريع عمليات Canvas يختلفان حسب مستوى Android والجهاز. [Android hardware acceleration](https://developer.android.com/develop/ui/views/graphics/hardware-accel)

## ما يفعله التطبيق الآن

واجهة MY SPACE ترسم المشهد عبر Canvas 2D داخل WebView، ويعمل راسم الكواكب ثلاثي الأبعاد الحالي كـpixel shader برمجي على `ImageData`، وليس كـGLSL/WebGL. لذلك أضيفت ظلال هندسية خفيفة تتناسب مع المسار الموجود بدل إدخال خريطة ظل GPU تتطلب إعادة بناء كبيرة واختبارًا على أجهزة متعددة.

- الكوكب الكروي يحسب موضع تقاطع شعاع الضوء مع مستوى الحلقة. إذا مرّ الشعاع عبر نطاق الحلقات، ينخفض الضوء المباشر على البكسل مع إبقاء الإضاءة المحيطة، فتظهر ظلال الحلقات على السطح باتجاه الضوء وبحواف مموهة.
- جسيمات المدار تُرسم ككرات صغيرة ذات إضاءة اتجاهية ونواة معتمة قليلًا وظل تماس ناعم. صور الجسيمات تُخزّن مؤقتًا بحسب اللون وتُعاد الاستفادة منها بدل توليد تدرجات جديدة لكل جسيم في كل إطار.
- الأشكال غير الكروية تحتفظ بظل التماس والإضاءة المحيطية، وتُضاف إليها طبقة سطح مقيّدة بقناع الشكل. تحافظ الكرات على تظليل السطح وخيارات المادة، بما فيها الزجاج والمعدن والغاز والرمل والنمط الرقمي.
- ويدجت Android يبقى Bitmap أصليًا ثابتًا، مع إضاءة كروية وطبقات سطح منحنية. لا يحاول تشغيل WebGL داخل RemoteViews.

هذه تحسينات **مستوحاة من الفيزياء** وليست خريطة ظل ثلاثية الأبعاد كاملة: لا تزال الأجسام والقمر والجسيمات لا تتبادل كل الظلال الممكنة عبر مشهد GPU موحّد. تنفيذ ذلك بدقة أعلى يستلزم مسار WebGL2 اختياريًا بخريطة ظل وPCF، مع Canvas fallback عند تعذر إنشاء السياق أو فقدانه، واختبارات زمن الإطار والبطارية على أجهزة حقيقية. لا يُنصح بفرض هذا المسار على كل البطاقات الصغيرة في الواجهة.

## المراجع

- [GPU Gems, Chapter 11: Shadow Map Antialiasing](https://developer.nvidia.com/gpugems/gpugems/part-ii-lighting-and-shadows/chapter-11-shadow-map-antialiasing)
- [GPU Gems 2, Chapter 17: Efficient Soft-Edged Shadows Using Pixel Shader Branching](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-17-efficient-soft-edged-shadows-using)
- [Android Developers: Hardware acceleration](https://developer.android.com/develop/ui/views/graphics/hardware-accel)
- [MJP: Shadow Maps — Filtering and bias tradeoffs](https://therealmjp.github.io/posts/shadow-maps/)
