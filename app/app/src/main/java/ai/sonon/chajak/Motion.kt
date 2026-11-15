package ai.sonon.chajak

import android.animation.TimeInterpolator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/**
 * 마이크로 인터랙션 — 화면의 작은 동작들.
 *
 * 차작은 운전석에서 쓰는 앱이라 "예쁜 모션"이 아니라 "확인됨"이 목적이 있다.
 * (개발 37 UX 가이드 1절 2 — "눈 대신 귀")
 *
 * 원칙 세 가지:
 *  1. 소리를 보조한다. 눈으로 못 보는 순간(소리만 들을 때)에 상태를 알려줄 수 있어야 한다.
 *  2. 주행 중에는 조용하다. 화면이 통째로 바뀌는 애니메이션은 도로교통법상 위험하다
 *     (2-5 주행 중 금지 패턴 — 깜빡이거나 화면 전체가 바뀌는 애니메이션).
 *  3. 색만으로 뜻을 전하지 않는다. 움직임은 글자·아이콘과 함께 동작한다(9절 접근성).
 *
 * 참조한 원본 저장소:
 *  - ddoemonn/interior — "클릭 직후 반초"의 마이크로 인터랙션 54종
 *  - Jakubantalik/transitions.dev — 프로덕션용 CSS 트랜지션 43종의 타이밍 규칙
 *  - iurvish/uselayouts — 표현형 마이크로 인터랙션 70종
 *
 * 여기서 가져온 것은 "터치·상태 전환의 완결감"에 해당하는 것뿐이고,
 * 화면을 장식하는 효과는 가져오지 않았다.
 */
object Motion {

    // ── 지속 시간 ────────────────────────────────────────────
    // 전부 짧다. 운전자가 눈을 떼는 사이에 끝나야 한다.
    /** 눌림 즉시 반응. */
    const val PRESS_MS = 90L
    /** 항목이 새로 등장. */
    const val ENTER_MS = 180L
    /** 항목이 사라짐. 사라지는 걸 바로 지우면 놓친다. */
    const val EXIT_MS = 140L
    /** 단계·탭처럼 위치가 바뀌는 것. */
    const val MOVE_MS = 200L

    // ── 크기 ────────────────────────────────────────────────
    /** 눌릴 때 줄어드는 비율. 0.97 — 눈으로 hardly 보일 만큼 작고, 손끝으로는 느껴진다. */
    const val PRESS_SCALE = 0.97f
    /** 등장할 때 올라오는 거리(dp). 조금만 — 화면을 가리지 않으면서 "왔다"는 신호. */
    const val RISE_DP = 10

    // ── 이징 ────────────────────────────────────────────────
    /** 빠르게 시작해서 부드럽게 멈춘다. 손가락 반응에 잘 맞는다. */
    val OUT: TimeInterpolator = android.view.animation.DecelerateInterpolator(1.6f)
    /** 사라질 때. 부드럽게 빠진다. */
    val IN: TimeInterpolator = android.view.animation.AccelerateInterpolator(1.4f)

    /**
     * 눌림 표시 — 누른 것이 맞는지 손끝으로 안다.
     *
     * 색만 바뀌는 Pleos 기본 상태는 차가 흔들릴 때 확인이 어렵다.
     * 아주 살짝 줄었다 돌아오는 것으로 "눌렸다"를 알려준다.
     *
     * 단, 버튼 크기는 그대로다(터치 영역 규칙 — 줄인 만큼이 아니라 덧그리는 것).
     */
    // 여기서 클릭을 처리하지 않는다. onTouch 는 false 를 돌려주고 원래의 setOnClickListener 가 그대로 클릭을 받는다.
    // 그래서 performClick 을 부르지 않는다 — 부르면 클릭이 두 번(한 번은 우리가, 한 번은 원래 리스너가) 울린다.
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(PRESS_MS).setInterpolator(OUT).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(PRESS_MS).setInterpolator(OUT).start()
            }
            false   // 터치를 소비하지 않는다 — 원래의 클릭이 그대로 동작한다
        }
    }

    /**
     * 새 항목이 등장 — "새로 떴다"를 알린다.
     * 아래에서 조금 올라오며 나타난다.
     */
    fun enter(v: View, delayMs: Long = 0) {
        v.alpha = 0f
        v.translationY = RISE_DP.toFloat() * v.resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(ENTER_MS).setStartDelay(delayMs).setInterpolator(OUT).start()
    }

    /**
     * 자리만 바꾸기 — 사라지지 않고 사라지는 것 없이 자리만 바뀐다.
     *
     * 화면 전체를 갈아끼울 때는 이쪽만 쓴다. 위로 올라오는 [enter]와 겹치면
     * "화면이 통째로 바뀌는 애니메이션"이 되어(37 UX 가이드 2-5) 자리를 덮어버린다.
     */
    fun fade(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(OUT).start()
    }

    /**
     * 사라짐 — 사라지는 순간도 보이게 둔다.
     * 바로 지우면 사용자는 "원래 없었나" 하고 지나친다.
     */
    fun exit(v: View, then: (() -> Unit)? = null) {
        v.animate().alpha(0f).translationY(-RISE_DP.toFloat() * v.resources.displayMetrics.density)
            .setDuration(EXIT_MS).setInterpolator(IN)
            .withEndAction { then?.invoke() }
            .start()
    }

    /**
     * 채워지기 — 막대가 왼쪽부터 차오른다.
     *
     * "지금 어디까지 왔나"를 말로 읽지 않아도 눈으로 따라갈 수 있게 한다.
     * 한 번만 차오르고 멈춘다. 계속 차오르면 반복이 되고 반복은 경고다(37 UX 가이드 2-5).
     */
    fun fill(v: View) {
        v.pivotX = 0f
        v.scaleX = 0f
        v.animate().scaleX(1f).setDuration(MOVE_MS).setInterpolator(OUT).start()
    }
}
