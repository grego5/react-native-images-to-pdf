require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "NitroImagesToPdf"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = "https://github.com/grego5/react-native-images-to-pdf"
  s.license      = { :type => "MIT" }
  s.authors      = "grego5"
  s.source       = {
    :git => "https://github.com/grego5/react-native-images-to-pdf.git",
    :tag => s.version.to_s
  }
  s.platforms    = { :ios => "13.4" }

  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.swift_version = "5.9"
  s.dependency "NitroModules"

  load "nitrogen/generated/ios/NitroImagesToPdf+autolinking.rb"
  add_nitrogen_files(s)
end
