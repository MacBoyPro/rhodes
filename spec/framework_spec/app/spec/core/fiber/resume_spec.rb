# XXX Crashes
#require File.dirname(File.join(__rhoGetCurrentDir(), __FILE__)) + '/../../spec_helper'
#require File.dirname(File.join(__rhoGetCurrentDir(), __FILE__)) + '/shared/resume'
#
#ruby_version_is "1.9" do
#  describe "Fiber#resume" do
#
#    it_behaves_like(:resume, :transfer)
#
#    it "returns control to the calling Fiber if called from one" do
#      fiber1 = Fiber.new { :fiber1 }
#      fiber2 = Fiber.new { fiber1.resume; :fiber2 }
#      fiber2.resume.should == :fiber2
#    end
#
#    it "raises a FiberError if the Fiber has transfered control to another Fiber" do
#      fiber1 = Fiber.new { true }
#      fiber2 = Fiber.new { fiber1.transfer; Fiber.yield }
#      fiber2.resume
#      lambda { fiber2.resume }.should raise_error(FiberError)
#    end
#  end
#end
