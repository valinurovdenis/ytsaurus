#!/usr/bin/env bash

script_name=$0

image_tag=""
ytsaurus_source_path="."
ytsaurus_build_path="."
output_path="."

print_usage() {
    cat << EOF
Usage: $script_name [-h|--help]
                    [--ytsaurus-source-path /path/to/ytsaurus.repo (default: $ytsaurus_source_path)]
                    [--ytsaurus-build-path /path/to/ytsaurus.build (default: $ytsaurus_build_path)]
                    [--output-path /path/to/output (default: $output_path)]
                    [--image-tag some-tag (default: $image_tag)]
EOF
    exit 1
}

# Parse options
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --ytsaurus-source-path)
        ytsaurus_source_path="$2"
        shift 2
        ;;
        --ytsaurus-build-path)
        ytsaurus_build_path="$2"
        shift 2
        ;;
        --output-path)
        output_path="$2"
        shift 2
        ;;
        --image-tag)
        image_tag="$2"
        shift 2
        ;;
        -h|--help)
        print_usage
        shift
        ;;
        *)  # unknown option
        echo "Unknown argument $1"
        print_usage
        ;;
    esac
done


ytserver_all="${ytsaurus_build_path}/yt/yt/server/all/ytserver-all"
init_queue_agent_state="${ytsaurus_source_path}/yt/python/yt/environment/init_queue_agent_state.py"

ytsaurus_credits="${ytsaurus_source_path}/yt/docker/ytsaurus/credits"

dockerfile="${ytsaurus_source_path}/yt/docker/queue-agent/Dockerfile"

cp ${ytserver_all} ${output_path}
cp ${init_queue_agent_state} ${output_path}

cp -r ${ytsaurus_build_path}/ytsaurus_python ${output_path}
cp ${dockerfile} ${output_path}

mkdir ${output_path}/credits
cp -r ${ytsaurus_credits}/ytserver-all.CREDITS ${output_path}/credits

cd ${output_path}

docker build -t ytsaurus/queue-agent:${image_tag} .
