# ملاحظات تصميم ويدجت MY SPACE

- يوصي Android بتحسين نوع وتواتر وتوقيت تحديثات الودجت، إذ قد تكون التحديثات الكاملة مكلفة. حدّ `updatePeriodMillis` الأدنى 30 دقيقة، ويمكن للمستخدم التفاعل مباشرةً مع الودجت لتحديثه. [1]
- إعدادات الودجت تُنفَّذ عبر `Activity` يطلقها مضيف الشاشة عند إنشاء نسخة قابلة للتخصيص؛ بذلك يمكن تخصيص جرم ومنطقة زمنية لكل `appWidgetId`. [2]
- `TextClock` يعرض الوقت/التاريخ، يحترم إعداد 12/24 ساعة، ويتحدّث أثناء ظهوره. يمكن تعيين منطقته الزمنية عبر `setTimeZone(String)`؛ وتُعلِّم الشيفرة المصدرية الرسمية الدالة بـ`@RemotableViewMethod`، فتصلح لتعيينها بواسطة `RemoteViews.setCharSequence`. [3] [4]
- بما أن سطح الودجت الأصلي هو `RemoteViews` محدود، تُعرض لقطة كوكب/نجم مولَّدة بـBitmap، وتُفتح شاشة الجرم ثلاثية الأبعاد داخل التطبيق عند النقر. يظل `TextClock` حيًا دون مؤقّت خدمة مستمر؛ ويدور الرسم داخل الودجت عند تحديثه أو التفاعل معه، لا كرسوم مستمرة بمعدل الإطارات.

[1]: https://developer.android.com/develop/ui/views/appwidgets/advanced "Create an advanced widget"
[2]: https://developer.android.com/develop/ui/views/appwidgets/configuration "Enable users to configure app widgets"
[3]: https://developer.android.com/reference/android/widget/TextClock "TextClock API"
[4]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextClock.java "Android TextClock source"
