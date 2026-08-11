package com.hamhuo.tplanner

/** Second destination: select one of the three task types already supported by the phone. */
class CreateTypeActivity : WearPageActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_type_page)
        val route = intent.creationRouteOrNull() ?: run {
            finish()
            return
        }

        val content = creationContent().apply {
            addView(creationTopSpacer())
            addView(creationHeading(getString(R.string.task_create_type_page)))
            addView(creationTypeButton(
                R.string.task_create_type_event,
                R.string.task_create_type_event_description,
            ) { openTime(route, TYPE_EVENT) })
            addView(creationTypeButton(
                R.string.task_create_type_status,
                R.string.task_create_type_status_description,
            ) { openTime(route, TYPE_STATUS) })
            addView(creationTypeButton(
                R.string.task_create_type_task,
                R.string.task_create_type_task_description,
            ) { openTime(route, TYPE_TASK) })
            addView(creationBottomSpacer())
        }
        setContentView(creationScrollPage(content))
    }

    private fun openTime(route: CreationRoute, type: String) {
        @Suppress("DEPRECATION")
        startActivityForResult(
            CreateTimeActivity.createIntent(this, route.copy(type = type)),
            REQUEST_CREATION_NEXT,
        )
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        propagateCreationResult(requestCode, resultCode)
    }

    companion object {
        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateTypeActivity::class.java).putCreationRoute(route)
    }
}
