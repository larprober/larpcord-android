# Nothing is called from JavaScript by reflection: the page talks to the app through
# WebViewCompat.addWebMessageListener, which R8 handles like any other Kotlin code.
