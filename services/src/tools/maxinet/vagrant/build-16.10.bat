#!/bin/bash

# ==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==
# INTRODUCTION:
# - This will call packer with default settings for a standard maxinet VM to be
#   used for OpenKilda testing.
# - The options can be easily modified to suit your needs.
# - The output is a vagrant box, which can be added locally.
# - The OpenKilda project uses this to create the box that is pushed to atlas,
#   and is used when calling "vagrant box add openkilda/maxinet"
#
# NB:
# - If you're experimenting with building boxes, download the ubuntu iso and
#   place it in either this folder or define "iso_path" before calling this
#   script. "iso_path" will cause packer to look for the iso in that folder.
#   If there is no ISO, it'll just grab it from the internet (650MB+).
# - vagrant, packer, and virtualbox need to be pre-installed
# ==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==•==

SET root=%cd%
IF NOT DEFINED iso_path (SET iso_path=%root%)
IF NOT DEFINED custom_script (SET custom_script=%root%\install_scripts\all.sh)

cd packer\ubuntu && packer build -only=virtualbox-iso -var-file=ubuntu1610.json ^
    -var "iso_path=${root}" ^
    -var "hostname=openkilda" ^
    -var "ssh_username=kilda" ^
    -var "ssh_password=kilda" ^
    -var "vagrantfile_template=%root%\vagrantfile.template" ^
    -var "cpus=4" ^
    -var "memory=4096" ^
    -var "headless=true" ^
    -var "custom_script=%custom_script%" ^
    ubuntu.json
