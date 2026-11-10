# 차작 release 축소 규칙.
# 화면은 코드로 만들고(리플렉션 없음) JSON 은 org.json(안드로이드 내장)이라 따로 지킬 것이 적다.
# Media3·Guava 는 라이브러리에 들어 있는 규칙을 쓴다.
# 매니페스트에 적힌 서비스·액티비티는 R8 이 알아서 남긴다.

# 오류 로그의 줄 번호를 남긴다(난독화된 이름은 mapping.txt 로 되돌린다)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
