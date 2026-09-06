'use strict';

// On-device Node.js compatibility shim for Android apps running a Termux-userland Node
// (loaded via NODE_OPTIONS="-r <this file>"). Patches the small set of POSIX/runtime gaps
// that trip up npm packages and build tooling under Android's Bionic libc:
//   - process.platform reports "android" (no package knows it)  -> report "linux"
//   - os.cpus() can come back empty on some kernels             -> keep it an array
//   - os.networkInterfaces() can come back empty before /proc is mounted fully
// The shim is deliberately dependency-free and safe to require twice.

var isAndroid = typeof process.platform === 'string' && process.platform === 'android';

if (isAndroid) {
  // npm's node-gyp, esbuild, rollup-native, sharp, etc. switch on platform === 'win32'
  // vs every-posix. None of them understands 'android'; they'd pick android tarballs that
  // the Termux repos don't provide. Report the glibc-ish name so the linux path is chosen.
  try {
    Object.defineProperty(process, 'platform', { value: 'linux', configurable: true });
  } catch (e) { /* frozen process object — never happens on Node, but do not crash */ }
}

var os = require('os');

// Node's os.cpus() on Android parses /proc/cpuinfo; if the file is present but in the
// hardware-architecture variant (no "processors" count), it may short-circuit to an empty
// array. Hardening tools (jest workers, node-gyp configure) treat a non-array as fatal.
var cpus = null;
try { cpus = os.cpus(); } catch (e) { cpus = null; }
if (!Array.isArray(cpus)) {
  var realCpus = os.cpus.bind(os);
  os.cpus = function cpusCompat() {
    try {
      var v = realCpus();
      if (Array.isArray(v)) return v;
    } catch (e) { /* fallthrough */ }
    return [{ model: 'Android', speed: 0, times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 } }];
  };
}

// os.networkInterfaces() can return {} before the network stack settles; zero-length is
// tolerated by most code, but some daemons refuse to bind a listener when no interface is
// reported. Provide a best-effort loopback fallback rather than an empty object.
var ifaces = null;
try { ifaces = os.networkInterfaces(); } catch (e) { ifaces = null; }
if (!ifaces || Object.keys(ifaces).length === 0) {
  var realNet = os.networkInterfaces.bind(os);
  os.networkInterfaces = function networkInterfacesCompat() {
    try {
      var v = realNet();
      if (v && Object.keys(v).length > 0) return v;
    } catch (e) { /* fallthrough */ }
    return { lo: [{ address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4', mac: '00:00:00:00:00:00', internal: true }] };
  };
}

// os.homedir() may point at the Termux $HOME which some tools read before env is applied;
// normalise through $HOME when present without touching the real fallback chain.
if (typeof os.homedir === 'function' && process.env.HOME) {
  try {
    var realHome = os.homedir.bind(os);
    os.homedir = function homedirCompat() {
      try {
        var v = realHome();
        if (v) return v;
      } catch (e) { /* fallthrough */ }
      return process.env.HOME;
    };
  } catch (e) { /* ignore */ }
}