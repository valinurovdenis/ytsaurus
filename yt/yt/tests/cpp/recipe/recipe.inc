DEPENDS(
    yt/yt/tests/cpp/recipe
    yt/yt/packages/tests_package
)

REQUIREMENTS(
    cpu:4
    ram_disk:4
)

USE_RECIPE(
    yt/yt/tests/cpp/recipe/recipe --cluster-config config.yson
)
