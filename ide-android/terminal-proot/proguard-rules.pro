# :terminal-proot has no Java/Kotlin code — pure JNI/jniLibs. Nothing to obfuscate here.
# The proot + talloc native code is stripped at the C level by the upstream CMakeLists.txt
# POST_BUILD command (see src/main/cpp/CMakeLists.txt lines 290-302).
