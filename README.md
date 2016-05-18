debian-package-builder
======================

debian-package-builder is Jenkins plugin for building debian (.deb) packages.
It rocks. really.

Prerequisites
=


* Jenkins user need to have rights to run (maybe via sudo):
```
apt-get -y update
apt-get -y install aptitude pbuilder
pbuilder-satisfydepends
```

Configuration
=

* You need to define a GPG key in Global Jenkins configuration (located for instance at https://localhost/configure) in **Debian Package Builder**'s section.
    * Help can be found [here](https://keyring.debian.org/creating-key.html)
* In the **Build debian package** build step (located for instance at https://localhost/view/All/job/job1/configure), check **GPG sign package?** to enable signing

