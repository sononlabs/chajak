package ai.sonon.chajak

import android.content.Context
import android.graphics.Rect
import android.widget.EditText
import android.widget.ScrollView

/**
 * 포커스를 따라 저절로 스크롤하지 않는 ScrollView(2026-11-10 사용자: "마우스 휠로 내려도 자동으로 다시 올라간다").
 * 마우스로 누르면 터치 모드가 아니라 눌렀던 버튼에 포커스가 남고, 재생 시간 글자처럼 화면이 조금만 다시 배치돼도
 * ScrollView 가 그 버튼이 보이도록 되돌아갔다. 글자 입력 칸만은 키보드에 가리지 않게 따라간다.
 */
class NoJumpScrollView(context: Context) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        if (findFocus() is EditText) super.computeScrollDeltaToGetChildRectOnScreen(rect) else 0
}
